package ch.etasystems.pirol.ml

import android.util.Log
import ch.etasystems.pirol.audio.AudioChunk
import ch.etasystems.pirol.audio.dsp.AudioDspUtils
import ch.etasystems.pirol.audio.dsp.AudioResampler
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.selects.select
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Coroutine-basierter Worker fuer die Inference-Pipeline.
 *
 * Ab T110 zwei getrennte Pfade:
 * - **Audio-Persistenz** (Collector-Coroutine, [processChunk]): AudioChunk → ChunkAccumulator →
 *   PersistHop → [onAudioPersist]. Jedes Sample genau einmal, in Reihenfolge, nie durch
 *   Inference verzoegert.
 * - **Analyse** (eigene Coroutine, [runAnalysisLoop]): 3-s-Fenster im Hop-Raster (Overlap
 *   0/50/67 %) → Puffer (1 Platz, aeltestes Fenster wird bei Stau verworfen) → Resampling →
 *   ONNX → Regionalfilter → [onWindowAnalyzed] (nur bei Treffern).
 *
 * T29: Energiesparmodus — Fenster werden uebersprungen, wenn seit dem letzten analysierten
 * Fenster weniger als inferenceIntervalMs Aufnahmezeit vergangen ist. Intervalle <= 3 s
 * (Profil "Genau") analysieren jedes Fenster.
 *
 * T110 Auto-Fallback: siehe [OverlapFallback]. Reduktion gilt bis reset() (Session-Ende).
 *
 * @param onAudioPersist schreibt PCM in die Session; liefert den WAV-Offset (s) VOR dem Append
 *   oder null wenn keine Session aktiv ist
 * @param onOverlapReduced einmal pro Session bei der ersten automatischen Reduktion (neuer Wert in %)
 * @param onWindowAnalyzed AudioBlock des Fensters (inkl. Embedding) + Detektionen (nie leer)
 */
class InferenceWorker(
    private val classifier: AudioClassifier,
    private val regionalFilter: RegionalSpeciesFilter,
    var config: InferenceConfig = InferenceConfig.DEFAULT,
    private val onAudioPersist: (ShortArray) -> Float?,
    private val onOverlapReduced: (Int) -> Unit = {},
    private val onWindowAnalyzed: (AudioBlock, List<DetectionResult>) -> Unit
) {
    companion object {
        private const val TAG = "InferenceWorker"
        private const val STATS_EVERY_WINDOWS = 20
        private const val MAX_ANCHORS = 16

        /**
         * Resampling + DSP-Vorverarbeitung eines Fensters (T34/T133), identisch fuer Haupt- und
         * Zweitmodell (T224): Hochpass 200 Hz (optional), Peak-Normalisierung, Limiter.
         */
        internal fun prepareInput(
            pcm: ShortArray,
            sampleRate: Int,
            targetRate: Int,
            mode: AudioResampler.Mode,
            highpass: Boolean
        ): FloatArray {
            val resampled = AudioResampler.resample(pcm, sampleRate, targetRate, mode)
            if (highpass) {
                AudioDspUtils.highpassFilter(resampled, targetRate, 200f)
            }
            AudioDspUtils.peakNormalize(resampled, 0.95f)
            AudioDspUtils.brickwallLimit(resampled, 1.0f)
            return resampled
        }
    }

    /** Fenster inkl. Zuordnung zur Aufnahme, wird an die Analyse-Coroutine uebergeben */
    private class WindowJob(
        val window: AnalysisWindow,
        val overlapPercent: Int,
        val hopMs: Int,
        val budgetMs: Long,
        /** Wanduhr-Zeit (Epoch ms) des Fensterstarts */
        val startEpochMs: Long,
        /** Offset des Fensterstarts in recording.wav (s), null = keine Session */
        val recordingOffsetSec: Float?,
        /** T118: Fenster aus dem Preroll-Rueckstand (kein Echtzeit-Budget, wird nie verworfen) */
        val backlog: Boolean = false
    )

    /** Zuordnung Stream-Index → WAV-Offset, gesetzt beim Persistieren eines Hops */
    private class Anchor(val streamIndex: Long, val offsetSec: Float?)

    // T151a: wird bei anderer Fensterlaenge des Modells neu angelegt
    private var accumulator = ChunkAccumulator(windowDurationMs = classifier.windowMs)
    // T117: processChunk und flushPendingAudio teilen den Accumulator (Collector vs. Stop-Pfad)
    private val persistLock = Any()
    private val fallback = OverlapFallback()

    // Kapazitaet 1: max. ein wartendes Fenster, bei Stau wird das aelteste verworfen
    private val windowChannel = Channel<WindowJob>(capacity = 1)
    private val droppedWindows = AtomicInteger(0)

    // T118: Preroll-Fenster (Rueckstand) — unbegrenzt, Laenge durch Preroll begrenzt (max. 30 s → ~28
    // Fenster bei 67 %), werden VOR Live-Fenstern analysiert und nie verworfen.
    private val backlogChannel = Channel<WindowJob>(capacity = Channel.UNLIMITED)
    private val backlogPending = AtomicInteger(0)
    // T118: Live-Fenster, die waehrend des Preroll-Nachholens verworfen wurden (zaehlen nicht fuer den Fallback)
    private val droppedDuringBacklog = AtomicInteger(0)

    /** Obergrenze durch Auto-Fallback (100 = keine Begrenzung). Schreibt Analyse, liest Collector. */
    @Volatile
    private var overlapCap: Int = 100
    @Volatile
    private var reductionNotified = false

    // Nur Collector-Coroutine
    private val anchors = ArrayDeque<Anchor>()
    private var lastAnalyzedStart = -1L
    private var activeOverlap = 0

    // Nur Analyse-Coroutine
    private var analyzedWindows = 0

    private fun effectiveOverlap(): Int =
        OverlapFallback.normalize(minOf(config.overlapPercent, overlapCap))

    /**
     * Verarbeitet einen AudioChunk (Collector-Coroutine). Kehrt ohne Inference zurueck.
     *
     * @param nowEpochMs Wanduhr (Epoch ms) am ENDE des Chunks — Basis fuer Fenster-Zeitstempel
     * @param backlog T118: true fuer den Preroll — vollstaendige Fenster daraus werden alle analysiert
     */
    fun processChunk(
        chunk: AudioChunk,
        nowEpochMs: Long = System.currentTimeMillis(),
        backlog: Boolean = false
    ) = synchronized(persistLock) {
        // Overlap-Stufe anwenden (Setting oder Auto-Fallback)
        val overlap = effectiveOverlap()
        if (overlap != activeOverlap) {
            Log.i(TAG, "Overlap $activeOverlap% → $overlap%")
            activeOverlap = overlap
        }
        // T151a: Fensterlaenge des Modells hat gewechselt (Modellwechsel) → Rest sichern, neu anlegen
        val windowMs = classifier.windowMs
        if (windowMs != accumulator.windowDurationMs) {
            Log.i(TAG, "Fensterlaenge ${accumulator.windowDurationMs} ms → $windowMs ms")
            accumulator.flushPending()?.let { onAudioPersist(it.pcm) }
            accumulator = ChunkAccumulator(windowDurationMs = windowMs)
            anchors.clear()
            lastAnalyzedStart = -1L
        }
        accumulator.hopMs = config.hopMsFor(overlap, windowMs)

        val result = try {
            accumulator.feed(chunk)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Samplerate-Wechsel erkannt, Accumulator wird zurueckgesetzt: ${e.message}")
            // Bereits gesammelte Samples nicht verlieren
            accumulator.flushPending()?.let { onAudioPersist(it.pcm) }
            accumulator.reset()
            anchors.clear()
            lastAnalyzedStart = -1L
            accumulator.hopMs = config.hopMsFor(overlap, windowMs)
            accumulator.feed(chunk)
        }

        // 1. Persistenz zuerst — unabhaengig von der Analyse
        for (hop in result.hops) {
            val offsetSec = onAudioPersist(hop.pcm)
            anchors.addLast(Anchor(hop.startIndex, offsetSec))
            while (anchors.size > MAX_ANCHORS) anchors.removeFirst()
        }

        // 2. Analyse-Fenster einreihen
        val nowEpoch = nowEpochMs
        for (window in result.windows) {
            val sr = window.sampleRate
            val hopMs = config.hopMsFor(overlap, accumulator.windowDurationMs)
            val intervalMs = config.inferenceIntervalMs
            // T29: Energiesparmodus ueber Aufnahmezeit (Sample-Raster), nicht ueber Wanduhr
            if (intervalMs > CHUNK_DURATION_MS && lastAnalyzedStart >= 0 &&
                (window.startIndex - lastAnalyzedStart) < sr.toLong() * intervalMs / 1000
            ) {
                continue
            }
            lastAnalyzedStart = window.startIndex

            val anchor = anchors.lastOrNull { it.streamIndex <= window.startIndex }
            val recordingOffsetSec = anchor?.offsetSec?.let {
                it + (window.startIndex - anchor.streamIndex).toFloat() / sr
            }
            val startEpochMs = nowEpoch - (accumulator.totalSamples - window.startIndex) * 1000 / sr
            val budgetMs = if (intervalMs > CHUNK_DURATION_MS) maxOf(hopMs.toLong(), intervalMs) else hopMs.toLong()

            val job = WindowJob(window, overlap, hopMs, budgetMs, startEpochMs, recordingOffsetSec, backlog)
            if (backlog) {
                backlogPending.incrementAndGet()
                backlogChannel.trySend(job)  // UNLIMITED → immer erfolgreich
                continue
            }
            // T118: Verwerfen waehrend des Preroll-Nachholens separat zaehlen (kein Fallback-Ausloeser)
            val dropCounter = if (backlogPending.get() > 0) droppedDuringBacklog else droppedWindows
            if (!windowChannel.trySend(job).isSuccess) {
                // Stau: aeltestes wartendes Fenster verwerfen, neues einreihen
                if (windowChannel.tryReceive().isSuccess) dropCounter.incrementAndGet()
                if (!windowChannel.trySend(job).isSuccess) dropCounter.incrementAndGet()
            }
        }
    }

    /**
     * Analyse-Schleife — laeuft bis zur Cancellation der aufrufenden Coroutine.
     * Muss parallel zum Collector gestartet werden (z.B. launch im Inference-Job).
     */
    suspend fun runAnalysisLoop() {
        while (true) {
            currentCoroutineContext().ensureActive()
            // T118: Preroll-Fenster haben Vorrang (select bevorzugt die erste bereite Klausel)
            val job = backlogChannel.tryReceive().getOrNull() ?: select {
                backlogChannel.onReceive { it }
                windowChannel.onReceive { it }
            }
            try {
                analyze(job)
            } finally {
                if (job.backlog) backlogPending.updateAndGet { if (it > 0) it - 1 else 0 }  // reset() kann parallel auf 0 gesetzt haben
            }
        }
    }

    // T103a: Schluessel (Modell|Region) der letzten Zaehlung nicht zugeordneter Regionsarten
    private var unmappedStatsKey: String? = null
    /** T133: Resampler-Log nur einmal pro Session */
    @Volatile private var resamplerLogged = false

    /**
     * T103a: Zaehlt einmal pro Modell/Region die Arten des Regionssets, die das aktive Modell
     * nicht kennt (andere Taxonomie, z.B. 3.0 final). Loesung ueber Aliase folgt in T105.
     */
    private fun logUnmappedRegionSpecies() {
        val labelNames = classifier.labelScientificNames
        if (labelNames.isEmpty()) return
        val key = "${classifier.modelId}|${regionalFilter.getCurrentRegionId()}"
        if (key == unmappedStatsKey) return
        unmappedStatsKey = key
        val region = regionalFilter.getSpeciesList()
        val before = region.filter { it !in labelNames }
        // T105: nach Alias-Aufloesung beide Seiten ueber den kanonischen Schluessel vergleichen
        val labelKeys = labelNames.mapTo(HashSet()) { SpeciesKey.of(it) }
        val after = region.filter { SpeciesKey.of(it) !in labelKeys }
        Log.i(TAG, "T103_STATS model=${classifier.modelId} region=${regionalFilter.getCurrentRegionId()} " +
            "unmapped=${before.size}/${region.size} examples=${before.take(5)}")
        Log.i(TAG, "T105_STATS model=${classifier.modelId} region=${regionalFilter.getCurrentRegionId()} " +
            "region_unmapped_before=${before.size} after=${after.size} aliases=${SpeciesKey.aliasCount()} " +
            "examples_after=${after.take(5)}")
    }

    /** Ergebnis eines Fensters: Detektionen (null = Fehler) + Embedding */
    private class WindowOutput(val detections: List<DetectionResult>?, val embedding: FloatArray?)

    /**
     * T221: Ein Fenster sofort analysieren (Rescan einer Aufnahme): gleiche Schritte wie live
     * (Resampling, DSP, Klassifikation, Regionsfilter, Zuordnung), aber ohne Puffer, Auto-Fallback
     * und Callback. [recordingOffsetSec] = Fensterstart auf der Session-Achse.
     */
    fun analyzeWindowNow(window: AnalysisWindow, startEpochMs: Long, recordingOffsetSec: Float): List<DetectionResult> {
        val hopMs = config.hopMsFor(config.overlapPercent, classifier.windowMs)
        val job = WindowJob(window, config.overlapPercent, hopMs, hopMs.toLong(), startEpochMs, recordingOffsetSec, backlog = true)
        val t0 = System.nanoTime()
        val out = runWindow(job).detections.orEmpty()
        logLongWindow("rescan", (System.nanoTime() - t0) / 1_000_000, hopMs)
        return out
    }

    /** T231: Messlog fuer Modelle mit Fenstern ueber 3 s (Perch 5 s); BirdNET loggt hier nichts */
    private fun logLongWindow(role: String, ms: Long, hopMs: Int) {
        if (classifier.windowMs <= CHUNK_DURATION_MS) return
        Log.i(TAG, "T231_PERCH role=$role ms=$ms hop=$hopMs window=${classifier.windowMs} model=${classifier.modelId}")
    }

    private fun runWindow(job: WindowJob): WindowOutput {
        val window = job.window
        var detections: List<DetectionResult>? = null
        var embedding: FloatArray? = null
        try {
            // 1. Resampling: ShortArray → FloatArray @Modell-Rate (T103a: aus dem Profil, BirdNET 3: 32 kHz)
            val targetRate = classifier.inputSampleRate
            // T133: Modus aus Settings (AudioResampler.defaultMode); bei gleicher Rate Durchreichen
            val mode = AudioResampler.defaultMode
            if (!resamplerLogged) {
                resamplerLogged = true
                val taps = if (mode == AudioResampler.Mode.POLYPHASE) AudioResampler.tapsFor(window.sampleRate, targetRate) else 0
                Log.i(TAG, "T133 resampler=$mode in=${window.sampleRate} out=$targetRate taps=$taps")
            }
            // 1b. DSP-Vorverarbeitung auf Inference-Kopie (T34); T224: gemeinsam mit dem Zweitmodell
            val resampled = prepareInput(window.pcm, window.sampleRate, targetRate, mode, config.highpassEnabled)

            // 2. Klassifizierung (T71/T225: Alternativen-Untergrenze, T61: Embedding aus demselben Durchlauf)
            val output = classifier.classifyWithEmbedding(
                samples = resampled,
                sampleRate = targetRate,
                // T225: topK Detektionen plus topK Alternativen; splitByThreshold begrenzt wieder
                topK = config.topK * 2,
                // T211/T225: Untergrenze der Alternativen, nie ueber der Detektionsschwelle
                threshold = InferenceConfig.classifierThreshold(config.confidenceThreshold, config.alternativesFloor, classifier.minConfidence)
            )
            embedding = output.embedding
            logUnmappedRegionSpecies()
            detections = mapResults(filterRegional(output.results, job), job)
        } catch (e: Exception) {
            Log.e(TAG, "Inference fehlgeschlagen", e)
        }
        return WindowOutput(detections, embedding)
    }

    private fun analyze(job: WindowJob) {
        val window = job.window
        val t0 = System.nanoTime()
        val output = runWindow(job)
        val detections = output.detections
        val embedding = output.embedding
        val inferMs = (System.nanoTime() - t0) / 1_000_000
        logLongWindow("primary", inferMs, job.hopMs)

        // T110: Auto-Fallback + Statistik
        analyzedWindows++
        val dropped = droppedWindows.get()
        if (job.backlog) {
            // T118: Preroll-Nachholen hat kein Echtzeit-Budget → nicht in den Auto-Fallback einrechnen
            if (backlogPending.get() <= 1) logStats(job, dropped, "preroll_done dropped_during_preroll=${droppedDuringBacklog.get()}")
        }
        val reduced = if (job.backlog) null else fallback.evaluate(job.overlapPercent, job.budgetMs, inferMs, dropped)
        if (reduced != null && reduced < overlapCap && reduced < OverlapFallback.normalize(job.overlapPercent)) {
            overlapCap = reduced
            logStats(job, dropped, "fallback_to=$reduced")
            if (!reductionNotified) {
                reductionNotified = true
                onOverlapReduced(reduced)
            }
        } else if (analyzedWindows % STATS_EVERY_WINDOWS == 0) {
            logStats(job, dropped, null)
        }

        val dets = detections
        if (!dets.isNullOrEmpty()) {
            val floatSamples = FloatArray(window.pcm.size) { i ->
                window.pcm[i].toFloat() / Short.MAX_VALUE.toFloat()
            }
            Log.d(TAG, "Detektionen: ${dets.size} Arten " +
                    "(Top: ${dets.first().commonName} ${(dets.first().confidence * 100).toInt()}%)")
            onWindowAnalyzed(
                AudioBlock(floatSamples, window.sampleRate, pcm = window.pcm, embedding = embedding),
                dets
            )
        }
    }

    private fun logStats(job: WindowJob, dropped: Int, extra: String?) {
        Log.i(
            TAG,
            "T110_STATS overlap=${job.overlapPercent} hop_ms=${job.hopMs} " +
                    "infer_ms_median=${fallback.medianMs()} dropped_windows=$dropped " +
                    "analyzed_windows=$analyzedWindows" + (extra?.let { " $it" } ?: "")
        )
    }

    private fun filterRegional(classifierResults: List<AudioClassifier.ClassifierResult>, job: WindowJob): List<AudioClassifier.ClassifierResult> {
        // T190: Aufnahmedatum (lokal) des Fensters fuer die Listen-Saison
        val monthDay = if (config.respectListSeason) ListSeason.monthDay(job.startEpochMs) else null
        if (config.regionFilter != null && config.showOnlyFiltered) {
            // Nur plausible Arten durchlassen
            return classifierResults.filter { cr ->
                val plausible = regionalFilter.isPlausible(cr.scientificName, config.respectListSeason, monthDay)
                if (!plausible) {
                    Log.d(TAG, "Herausgefiltert (nicht regional plausibel): " +
                            "${cr.commonName} (${cr.scientificName})")
                }
                plausible
            }
        }
        // Alle behalten, aber ungewoehnliche Arten loggen
        if (config.regionFilter != null) {
            classifierResults.forEach { cr ->
                if (!regionalFilter.isPlausible(cr.scientificName, config.respectListSeason, monthDay)) {
                    Log.d(TAG, "Ungewoehnlich (nicht regional erwartet): " +
                            "${cr.commonName} (${cr.scientificName}) " +
                            "${(cr.confidence * 100).toInt()}%")
                }
            }
        }
        return classifierResults
    }

    /**
     * ClassifierResult → DetectionResult.
     * T110: timestampMs = Wanduhr des Fensterstarts; chunkStartSec/EndSec = Fenster in recording.wav
     * (nur mit aktiver Session, sonst Classifier-Werte wie bisher).
     */
    private fun mapResults(results: List<AudioClassifier.ClassifierResult>, job: WindowJob): List<DetectionResult> {
        val window = job.window
        val durationSec = window.pcm.size.toFloat() / window.sampleRate
        return results.map { cr ->
            DetectionResult(
                id = UUID.randomUUID().toString(),
                scientificName = cr.scientificName,
                commonName = cr.commonName,
                confidence = cr.confidence,
                timestampMs = job.startEpochMs,
                chunkStartSec = job.recordingOffsetSec ?: cr.timeStartSec,
                chunkEndSec = job.recordingOffsetSec?.let { it + durationSec } ?: cr.timeEndSec,
                sampleRate = window.sampleRate
            )
        }
    }

    /**
     * T117: Noch nicht persistierte Samples (Rest unter einem Hop, bis zu 3 s) in die Session schreiben.
     * Beim Stopp VOR reset() aufrufen, sonst fehlt das Ende der Aufnahme in recording.wav.
     */
    fun flushPendingAudio() = synchronized(persistLock) {
        accumulator.flushPending()?.let { hop ->
            onAudioPersist(hop.pcm)
            Log.d(TAG, "T117 Audio-Rest beim Stopp geschrieben: ${hop.pcm.size} Samples")
        }
        Unit
    }

    /** Pipeline zuruecksetzen (bei Recording-Stop/Neustart). Jobs muessen vorher gestoppt sein. */
    fun reset() {
        accumulator.reset()
        while (windowChannel.tryReceive().isSuccess) { /* wartende Fenster verwerfen */ }
        while (backlogChannel.tryReceive().isSuccess) { /* T118: wartende Preroll-Fenster verwerfen */ }
        backlogPending.set(0)
        droppedDuringBacklog.set(0)
        anchors.clear()
        lastAnalyzedStart = -1L
        fallback.reset()
        droppedWindows.set(0)
        overlapCap = 100
        reductionNotified = false
        analyzedWindows = 0
        resamplerLogged = false
        Log.d(TAG, "Pipeline zurueckgesetzt")
    }
}
