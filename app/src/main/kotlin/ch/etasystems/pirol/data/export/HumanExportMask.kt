package ch.etasystems.pirol.data.export

import android.content.Context
import android.util.Log
import ch.etasystems.pirol.audio.dsp.AudioResampler
import ch.etasystems.pirol.audio.dsp.SpeechBandMasker
import ch.etasystems.pirol.audio.export.ExcerptWav
import ch.etasystems.pirol.audio.export.FrameSpan
import ch.etasystems.pirol.audio.export.HumanWindowCollector
import ch.etasystems.pirol.audio.export.HumanWindowLine
import ch.etasystems.pirol.audio.export.HumanWindows
import ch.etasystems.pirol.audio.export.MaskedWavStream
import ch.etasystems.pirol.audio.export.MemoExclusion
import ch.etasystems.pirol.audio.export.SegmentedRecording
import ch.etasystems.pirol.audio.export.TimeSpan
import ch.etasystems.pirol.data.AppPreferences
import ch.etasystems.pirol.data.repository.ExportEntry
import ch.etasystems.pirol.data.repository.SessionManager
import ch.etasystems.pirol.ml.AudioClassifier
import ch.etasystems.pirol.ml.BirdNetV3Classifier
import ch.etasystems.pirol.ml.HumanLabels
import ch.etasystems.pirol.ml.InferenceWorker
import ch.etasystems.pirol.ml.ModelManager
import ch.etasystems.pirol.ml.ModelProfile
import ch.etasystems.pirol.rescan.RescanPlan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * T261: Menschliche Stimmen im Export ausblenden (Speichern unter, ZIP/Upload, Teilen eines Ausschnitts).
 *
 * Ablauf: Merkliste `human_windows.jsonl` lesen → nicht bewertete Bereiche mit dem aktiven Modell nachscannen
 * (Rohscore, vor Filter/Schwelle; Ergebnis wird an die Merkliste angehaengt) → Abschnitte mit Score >= Schwelle
 * plus Rand → WAV-Kopien mit [SpeechBandMasker] bearbeiten. Das Original bleibt unveraendert.
 *
 * T263: Memo-Dateien (Dateien unter memos/) werden nie bearbeitet; der Zeitraum eines live gesprochenen Memos wird von
 * den Abschnitten abgezogen ([MemoExclusion]). Kennt das aktive Modell keine Menschen, entscheidet der Aufrufer
 * ueber [Choice] (BirdNET-3-Scan mit eigener, danach geschlossener ORT-Session, oder ohne Ausblenden).
 */
class HumanExportMask(
    private val sessionManager: SessionManager,
    private val classifier: AudioClassifier,
    private val prefs: AppPreferences,
    private val context: Context? = null,
    private val modelManager: ModelManager? = null,
    /** T263: laeuft gerade eine Aufnahme (Live-Inferenz)? Dann kein zweites grosses Modell fuer den Scan */
    private val isRecordingRunning: () -> Boolean = { false }
) {
    /** Bewerten nicht moeglich (Modell fehlt oder kennt keine Menschen) */
    class UnavailableException : Exception("Menschen koennen mit dem aktiven Modell nicht erkannt werden")

    /** T263: Entscheid fuer einen Export, dessen Modell keine Menschen kennt */
    sealed class Choice {
        /** Aktives Modell (Standard) */
        data object Active : Choice()

        /** Scan mit dem BirdNET-3-Modell [modelFile] (Dateiname in filesDir/models) */
        data class BirdNet(val modelFile: String) : Choice()

        /** Ohne Ausblenden exportieren (Warnung wurde gezeigt) */
        data object Skip : Choice()

        companion object {
            /** Kurzform fuer Auftragsdaten (WorkManager): null/leer = Standard, "skip", "birdnet:<Datei>" */
            fun encode(c: Choice): String? = when (c) {
                Active -> null
                Skip -> "skip"
                is BirdNet -> "birdnet:" + c.modelFile
            }

            fun decode(s: String?): Choice = when {
                s == null -> Active
                s == "skip" -> Skip
                s.startsWith("birdnet:") && s.length > 8 -> BirdNet(s.substring(8))
                else -> Active
            }
        }
    }

    /** Vorbereiteter Export: Aufnahme + auszublendende Abschnitte (Session-Frames) */
    class Plan(val recording: SegmentedRecording, val sections: List<FrameSpan>) {
        val isEmpty: Boolean get() = sections.isEmpty()

        fun masker(): SpeechBandMasker =
            SpeechBandMasker(recording.sampleRate, sections, recording.totalFrames, readerOf(recording))
    }

    val enabled: Boolean get() = prefs.exportMaskHumans

    /**
     * T263: BirdNET-3-Modelldatei fuer den Ersatz-Scan, falls installiert und keine Aufnahme laeuft
     * (ein zweites grosses Modell neben der Live-Inferenz waere Speicher- und Rechenrisiko). Bevorzugt 3.0 final.
     */
    fun birdNetScanModelFile(): String? {
        val mm = modelManager ?: return null
        return pickBirdNetFile({ mm.getModelFile(it) != null }, isRecordingRunning())
    }

    /**
     * Plant das Ausblenden fuer eine Session. null = ausgeschaltet, uebersprungen ([Choice.Skip]) oder keine Aufnahme.
     * @param range nur dieser Bereich wird gebraucht (Ausschnitt); null = ganze Aufnahme
     * @param onProgress Fortschritt des Nachscannens 0..1 (nur wenn gescannt werden muss)
     * @param choice T263: Scan mit dem aktiven Modell (Standard), mit BirdNET 3 oder ohne Ausblenden
     * @throws UnavailableException wenn gescannt werden muesste, das gewaehlte Modell es aber nicht kann
     */
    suspend fun prepare(
        sessionDir: File,
        range: TimeSpan? = null,
        onProgress: (Float) -> Unit = {},
        recordingIn: SegmentedRecording? = null,
        choice: Choice = Choice.Active
    ): Plan? {
        if (!enabled || choice is Choice.Skip) return null
        val recording = recordingIn ?: sessionManager.loadRecording(sessionDir) ?: return null
        if (recording.sampleRate <= 0 || recording.totalFrames <= 0) return null
        val total = recording.durationSec
        val lines = HumanWindows.parse(sessionManager.readHumanWindows(sessionDir)).toMutableList()
        var gaps = HumanWindows.gaps(lines, total)
        if (range != null) {
            val lo = max(0f, range.startSec - HumanWindows.MARGIN_SEC)
            val hi = min(total, range.endSec + HumanWindows.MARGIN_SEC)
            gaps = gaps.mapNotNull { g -> TimeSpan(max(g.startSec, lo), min(g.endSec, hi)).takeIf { it.endSec > it.startSec } }
        }
        if (gaps.isNotEmpty()) {
            val t0 = System.currentTimeMillis()
            val scanned = scan(recording, gaps, onProgress, (choice as? Choice.BirdNet)?.modelFile)
            val gapSec = gaps.sumOf { it.durationSec.toDouble() }
            Log.i(TAG, "T261_SCAN session=${sessionDir.name} gapSec=${"%.1f".format(gapSec)} windows=${scanned.count { it.kind == HumanWindows.KIND_HUMAN }} ms=${System.currentTimeMillis() - t0}")
            lines += scanned
            try {
                sessionManager.appendHumanWindowLinesTo(sessionDir, HumanWindows.encode(scanned))
            } catch (e: Exception) {
                Log.w(TAG, "Merkliste nicht erweitert (Export laeuft trotzdem)", e)
            }
        }
        val raw = HumanWindows.sections(lines, prefs.exportHumanThreshold, total)
        // T263: Zeitraum live gesprochener Memos nicht daempfen
        val sections = MemoExclusion.subtract(raw, memoCuts(sessionDir, total))
        Log.i(TAG, "T261_PLAN session=${sessionDir.name} sections=${sections.size} maskedSec=${"%.1f".format(sections.sumOf { it.durationSec.toDouble() })}")
        return Plan(recording, HumanWindows.toFrames(sections, recording.sampleRate, recording.totalFrames))
    }

    /**
     * T263: Memo-Zeitraeume auf der Session-Achse. Nur wenn die Wanduhr von Sekunde 0 aus echten Detektionen
     * abgeleitet werden kann (Fensterstart-Zeitstempel minus chunkStartSec, deckt Preroll ab); sonst leer.
     */
    private suspend fun memoCuts(sessionDir: File, totalSec: Float): List<TimeSpan> {
        return try {
            val spans = sessionManager.loadMemoWallSpans(sessionDir)
            if (spans.isEmpty()) return emptyList()
            val dets = sessionManager.loadDetections(sessionDir)
            val epoch0 = if (dets.any { !it.manualEntry }) RescanPlan.epochAtZero(dets, null) else null
            MemoExclusion.toSessionSpans(spans, epoch0, totalSec).also {
                Log.i(TAG, "T263_MEMO session=${sessionDir.name} memos=${spans.size} cutSec=${"%.1f".format(it.sumOf { s -> s.durationSec.toDouble() })}")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "T263: Memo-Zeitraeume nicht lesbar, nichts abgezogen", e)
            emptyList()
        }
    }

    /** Ersetzt die WAV-Segmente in [entries] durch bearbeitete Kopien (Stream); andere Dateien unveraendert. */
    fun wrap(entries: List<ExportEntry>, plan: Plan?): List<ExportEntry> = wrapEntries(entries, plan)

    /** Bearbeitet einen Ausschnitt ab Session-Frame [startFrame] (Kopie; [pcm] bleibt unveraendert). */
    fun maskPcm(pcm: ExcerptWav.Pcm, startFrame: Long, plan: Plan?): ExcerptWav.Pcm {
        if (plan == null || plan.isEmpty || pcm.sampleRate != plan.recording.sampleRate) return pcm
        val masker = plan.masker()
        if (!masker.affects(startFrame, pcm.samples.size)) return pcm
        val copy = pcm.samples.copyOf()
        masker.process(startFrame, copy)
        return ExcerptWav.Pcm(copy, pcm.sampleRate)
    }

    /**
     * Bewertet die [gaps]: mit dem aktiven Modell oder (T263) mit einem eigenen BirdNET-3-Klassifizierer
     * ([altModelFile], eigene ORT-Session, nie neben einer laufenden Aufnahme, danach sofort geschlossen).
     */
    private suspend fun scan(
        recording: SegmentedRecording,
        gaps: List<TimeSpan>,
        onProgress: (Float) -> Unit,
        altModelFile: String? = null
    ): List<HumanWindowLine> {
        if (altModelFile == null) return scanWith(classifier, recording, gaps, onProgress)
        val mm = modelManager
        val ctx = context
        if (mm == null || ctx == null || isRecordingRunning()) throw UnavailableException()
        val profile = ModelProfile.byFileName(altModelFile) ?: throw UnavailableException()
        if (mm.getModelFile(altModelFile) == null || !mm.ensureLabels(profile)) throw UnavailableException()
        mm.markInUse(altModelFile)
        val alt = BirdNetV3Classifier(ctx, mm, null, altModelFile)
        try {
            classifier.releaseIfIdle()  // RAM der Live-Session freigeben, sie laedt bei Bedarf neu
            return scanWith(alt, recording, gaps, onProgress)
        } finally {
            alt.close()
            mm.unmarkInUse(altModelFile)
        }
    }

    /** Bewertet die [gaps] mit [cls] (wie live: Resampling + DSP, dann Rohscores). */
    private suspend fun scanWith(
        cls: AudioClassifier,
        recording: SegmentedRecording,
        gaps: List<TimeSpan>,
        onProgress: (Float) -> Unit
    ): List<HumanWindowLine> =
        withContext(Dispatchers.Default) {
            if (!cls.isModelAvailable()) throw UnavailableException()
            val sr = recording.sampleRate
            val windowSec = cls.windowMs / 1000f
            val windowFrames = (windowSec.toDouble() * sr).toLong()
            val starts = gaps.flatMap { g -> HumanWindows.scanStarts(g, windowSec) }
            val collector = HumanWindowCollector(cls.modelId)
            val highpass = prefs.loadInferenceConfig().highpassEnabled
            val targetRate = cls.inputSampleRate
            var indices: IntArray? = null
            var namesRef: List<String>? = null
            for ((i, startSec) in starts.withIndex()) {
                currentCoroutineContext().ensureActive()
                val from = (startSec.toDouble() * sr).toLong()
                val pcm = recording.readFrames(from, from + windowFrames) ?: continue
                val input = InferenceWorker.prepareInput(pcm.samples, sr, targetRate, AudioResampler.defaultMode, highpass)
                val table = cls.scoreAll(input, targetRate) ?: throw UnavailableException()
                if (namesRef !== table.names) {
                    namesRef = table.names
                    indices = HumanLabels.indicesOf(table.names)
                }
                val score = HumanLabels.score(table.scores, indices!!) ?: throw UnavailableException()
                collector.add(max(startSec, 0f), min(recording.durationSec, startSec + windowSec), score)
                onProgress((i + 1).toFloat() / starts.size)
            }
            collector.drain()
        }

    companion object {
        private const val TAG = "HumanExportMask"

        /**
         * T263: Waehlt die BirdNET-3-Datei fuer den Ersatz-Scan: 3.0 final (FP16, dann pruned) vor Preview 3.1;
         * null = keine installiert oder eine Aufnahme laeuft.
         */
        fun pickBirdNetFile(isInstalled: (String) -> Boolean, recordingRunning: Boolean): String? {
            if (recordingRunning) return null
            return listOf(ModelProfile.V3_FINAL_FP16, ModelProfile.V3_FINAL_FP16_PRUNED, ModelProfile.PREVIEW31_FP16, ModelProfile.PREVIEW31_FP32)
                .firstOrNull { isInstalled(it.fileName) }?.fileName
        }

        /**
         * Ersetzt die Aufnahme-Segmente (recording.wav, recording-002.wav ... im Wurzelordner) durch bearbeitete Kopien.
         * T263: alles andere, v.a. Dateien unter memos/, bleibt bitgenau unveraendert (dasselbe Objekt).
         */
        fun wrapEntries(entries: List<ExportEntry>, plan: Plan?): List<ExportEntry> {
            if (plan == null || plan.isEmpty) return entries
            val masker = plan.masker()
            return entries.map { e ->
                if (e.path.contains('/') || SegmentedRecording.segmentIndex(e.path) == null) return@map e
                val part = plan.recording.parts.firstOrNull { it.source.name == e.path } ?: return@map e
                if (!masker.affects(part.startFrame, part.frames.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())) return@map e
                ExportEntry(e.path) { MaskedWavStream(e.open(), part.startFrame, masker) }
            }
        }

        /** Leser fuer den Masker: Frames [from, to), ausserhalb der Aufnahme mit 0 aufgefuellt */
        fun readerOf(recording: SegmentedRecording): (Long, Long) -> ShortArray = { from, to ->
            val n = (to - from).toInt()
            val out = ShortArray(max(0, n))
            val a = max(0L, from)
            val b = min(recording.totalFrames, to)
            if (b > a) {
                recording.readFrames(a, b)?.samples?.let { s ->
                    System.arraycopy(s, 0, out, (a - from).toInt(), min(s.size, n - (a - from).toInt()))
                }
            }
            out
        }
    }
}
