package ch.etasystems.pirol.rescan

import ch.etasystems.pirol.audio.RecordingPipeline
import ch.etasystems.pirol.audio.export.SegmentedRecording
import ch.etasystems.pirol.data.repository.SessionFiles
import ch.etasystems.pirol.data.repository.SpeciesFilterInfo
import ch.etasystems.pirol.ml.AnalysisWindow
import ch.etasystems.pirol.ml.AudioClassifier
import ch.etasystems.pirol.ml.ClusterResult
import ch.etasystems.pirol.ml.DetectionListState
import ch.etasystems.pirol.ml.DetectionResult
import ch.etasystems.pirol.ml.InferenceConfig
import ch.etasystems.pirol.ml.InferenceWorker
import ch.etasystems.pirol.ml.RegionalSpeciesFilter
import ch.etasystems.pirol.ml.SecondaryCheck
import ch.etasystems.pirol.ml.SecondaryChecker
import ch.etasystems.pirol.ml.VerificationEvent
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** T221: Einstellungen und Ergebnis einer Rescan-Variante (`rescan-<id>.json`). */
@Serializable
data class RescanInfo(
    val id: String,
    /** ISO 8601 mit Offset (Lokalzeit) */
    val createdAt: String,
    val modelId: String? = null,
    val modelName: String,
    /** Filter wie in session.json (`groups`; Gruppenwahl in [speciesFilter]) */
    val regionFilter: String? = null,
    val confidenceThreshold: Float,
    val overlapPercent: Int,
    val speciesFilter: SpeciesFilterInfo? = null,
    val detectionCount: Int = 0,
    val transferredVerifications: Int = 0
)

/** T221: Abbruch, weil waehrend des Rescans eine Aufnahme gestartet wurde. */
class RescanAbortedException : Exception("Rescan abgebrochen: Aufnahme laeuft")

/**
 * T221: Rescan einer Aufnahme mit gewaehltem Klassifizierer, Liste, Schwelle und Overlap.
 *
 * Nutzt dieselbe Kette wie live: [InferenceWorker.analyzeWindowNow] (Resampling wie eingestellt, DSP,
 * Klassifikation, Regionsfilter), [RecordingPipeline.splitByThreshold] (Mehrfachdetektion,
 * gemeinsame Schwelle), Artnamen, Cluster/Dedup ueber [DetectionListState.addOrCluster]. Die
 * Fenster liegen auf der Session-Achse ueber alle WAV-Segmente (inkl. Preroll); Bezugszeit des
 * Clusters ist die Aufnahmezeit, nicht die Wanduhr. Schreibt nichts — siehe [RescanStore].
 */
class RescanRunner(
    private val classifier: AudioClassifier,
    regionalFilter: RegionalSpeciesFilter,
    private val config: InferenceConfig,
    private val resolveName: (String) -> String,
    private val clusterEnabled: Boolean,
    private val clusterRadiusM: Int,
    private val clusterTimeWindowMin: Int,
    /** T248: Zweitmodell (nur Anzeige, Felder secondary*); null = keins */
    private val secondary: SecondaryChecker? = null,
    private val secondaryOverlapPercent: Int = 0
) {
    class Result(
        /** Detektionen wie in detections.jsonl (Merges bereits im Anker), nach Startzeit sortiert */
        val detections: List<DetectionResult>,
        /** CLUSTER_MERGED-Events wie live */
        val mergeEvents: List<VerificationEvent>,
        val windows: Int
    )

    private val worker = InferenceWorker(
        classifier = classifier,
        regionalFilter = regionalFilter,
        config = config,
        onAudioPersist = { null },
        onWindowAnalyzed = { _, _ -> }
    )

    /**
     * @param epochAtZeroMs Wanduhr von Sekunde 0 der Session-Achse (siehe [RescanPlan.epochAtZero])
     * @param positionAt Position zur Wanduhr-Zeit (null = keine)
     * @param onProgress Fortschritt 0..1 nach jedem Fenster
     * @param isAborted true → [RescanAbortedException] (z.B. Aufnahme gestartet)
     */
    suspend fun run(
        recording: SegmentedRecording,
        epochAtZeroMs: Long,
        positionAt: (Long) -> Pair<Double, Double>?,
        onProgress: (Float) -> Unit = {},
        isAborted: () -> Boolean = { false },
        nowMs: () -> Long = { System.currentTimeMillis() }
    ): Result {
        val sr = recording.sampleRate
        val windowMs = classifier.windowMs
        val starts = RescanPlan.windowStarts(recording.totalFrames, sr, windowMs, config.overlapPercent, config.maxHopMs)
        val windowFrames = sr.toLong() * windowMs / 1000
        val threshold = InferenceConfig.effectiveThreshold(config.confidenceThreshold, classifier.minConfidence)
        val list = DetectionListState(maxDetections = Int.MAX_VALUE)
        // Wie detections.jsonl: neue Detektionen anhaengen, Merges ersetzen den Anker (Reihenfolge bleibt)
        val persisted = LinkedHashMap<String, DetectionResult>()
        val merges = mutableListOf<VerificationEvent>()
        val gate = secondary?.let { SecondaryCheck.OverlapGate(it.windowMs, secondaryOverlapPercent, config.maxHopMs) }

        for ((i, start) in starts.withIndex()) {
            currentCoroutineContext().ensureActive()
            if (isAborted()) throw RescanAbortedException()
            val pcm = recording.readFrames(start, start + windowFrames) ?: continue
            val startSec = (start.toDouble() / sr).toFloat()
            val startEpoch = epochAtZeroMs + start * 1000L / sr
            val raw = worker.analyzeWindowNow(AnalysisWindow(pcm.samples, sr, start), startEpoch, startSec)
            if (raw.isNotEmpty()) {
                val pos = positionAt(startEpoch)
                val located = if (pos == null) raw else raw.map { it.copy(latitude = pos.first, longitude = pos.second) }
                val named = RecordingPipeline.splitByThreshold(
                    located, threshold, config.alternativesFloor, config.topK, config.topK
                )
                    .map { RecordingPipeline.withResolvedNames(it, resolveName) }
                val targets = mutableListOf<SecondaryChecker.SecondaryWindow.Target>()
                for (det in named) {
                    when (val r = list.addOrCluster(det, clusterEnabled, clusterRadiusM, clusterTimeWindowMin,
                        nowMs = startEpoch + windowMs)) {
                        is ClusterResult.New -> {
                            persisted[r.detection.id] = r.detection
                            targets += SecondaryChecker.SecondaryWindow.Target(r.detection.id, det.scientificName)
                        }
                        is ClusterResult.Merged -> {
                            persisted[r.anchor.id] = r.anchor
                            merges += RecordingPipeline.clusterMergedEvent(r, nowMs())
                            targets += SecondaryChecker.SecondaryWindow.Target(r.anchor.id, det.scientificName)
                        }
                    }
                }
                if (secondary != null && gate != null) {
                    checkSecondary(secondary, gate, targets, recording, start, pcm.samples, startSec, list, persisted)
                }
            }
            onProgress((i + 1).toFloat() / starts.size)
        }
        return Result(persisted.values.sortedBy { it.chunkStartSec }, merges, starts.size)
    }

    /**
     * T248: Zweitmodell fuer ein Fenster mit Detektionen (wie live: [SecondaryCheck.OverlapGate], bestes Fenster
     * des Clusters via [SecondaryCheck.shouldApply]). Laengere Fenster (Perch 5 s) lesen echtes Umgebungsaudio
     * aus der Aufnahme, zentriert um das Hauptfenster; sonst wie live [SecondaryCheck.fitWindow].
     */
    private fun checkSecondary(
        checker: SecondaryChecker,
        gate: SecondaryCheck.OverlapGate,
        targets: List<SecondaryChecker.SecondaryWindow.Target>,
        recording: SegmentedRecording,
        start: Long,
        samples: ShortArray,
        startSec: Float,
        list: DetectionListState,
        persisted: MutableMap<String, DetectionResult>
    ) {
        val accepted = targets.filter { gate.accept(it.scientificName, startSec) }
        if (accepted.isEmpty()) return
        val sr = recording.sampleRate
        val target = SecondaryCheck.windowSamplesAt(checker.windowMs, sr)
        val context = if (target > samples.size) {
            val range = SecondaryCheck.contextRange(start, samples.size, target, 0L)
            // Aufnahmeende: nach links verschieben, damit keine Stille noetig ist
            val from = minOf(range.from, recording.totalFrames - target).coerceAtLeast(0L)
            recording.readFrames(from, from + target)?.samples?.takeIf { it.size == target }
                ?.let { SecondaryChecker.ContextAudio(it, "CENTERED", 0L) }
        } else null
        val window = SecondaryChecker.SecondaryWindow(samples, sr, startSec, config.highpassEnabled, accepted)
        for ((t, outcome) in checker.process(window, context)) {
            val update = { cur: DetectionResult ->
                if (SecondaryCheck.shouldApply(cur, startSec)) SecondaryCheck.apply(cur, checker.modelId, outcome) else null
            }
            // Cluster aus: die Liste haelt nur die neueste Detektion je Art, aeltere stehen nur in [persisted]
            val updated = list.modifyDetection(t.id, update) ?: persisted[t.id]?.let(update)
            if (updated != null) persisted[t.id] = updated
        }
    }
}

/** T221: Varianten im Session-Ordner schreiben, auflisten, loeschen (ueber [SessionFiles], File + SAF). */
object RescanStore {
    private val json = Json { prettyPrint = false; encodeDefaults = true }
    private val jsonPretty = Json { prettyPrint = true; encodeDefaults = true }
    private val jsonLenient = Json { ignoreUnknownKeys = true }

    /**
     * Schreibt eine neue Variante. Original-Dateien werden nicht beruehrt. Reihenfolge: Detektionen,
     * Verifikationen (Merges, dann Uebernahmen), zuletzt die Metadaten — erst damit ist die Variante sichtbar.
     */
    fun write(
        files: SessionFiles,
        info: RescanInfo,
        detections: List<DetectionResult>,
        mergeEvents: List<VerificationEvent>,
        transferEvents: List<VerificationEvent>
    ) {
        val id = info.id
        require(id !in RescanFiles.idsIn(files.listNames())) { "Variante $id existiert bereits" }
        try {
            files.writeReplacing(RescanFiles.detections(id), detections.joinToString("") { json.encodeToString(it) + "\n" })
            files.writeReplacing(
                RescanFiles.verifications(id),
                (mergeEvents + transferEvents).joinToString("") { json.encodeToString(it) + "\n" }
            )
            files.writeReplacing(RescanFiles.meta(id), jsonPretty.encodeToString(info))
        } catch (e: Exception) {
            // T240: halb geschriebene Variante aufraeumen (keine verwaisten Dateien ohne Meta)
            runCatching { delete(files, id) }
            throw e
        }
    }

    /** Alle lesbaren Varianten, aelteste zuerst. */
    fun list(files: SessionFiles): List<RescanInfo> =
        RescanFiles.idsIn(files.listNames()).mapNotNull { id ->
            runCatching { jsonLenient.decodeFromString<RescanInfo>(files.readText(RescanFiles.meta(id)) ?: return@mapNotNull null) }
                .getOrNull()?.copy(id = id)
        }

    /** Loescht alle Dateien der Variante (nie Original-Dateien). */
    fun delete(files: SessionFiles, id: String): Boolean {
        return RescanFiles.filesOf(id).map { files.delete(it) }.all { it }
    }
}
