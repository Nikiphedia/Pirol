package ch.etasystems.pirol.data.export

import android.util.Log
import ch.etasystems.pirol.audio.dsp.AudioResampler
import ch.etasystems.pirol.audio.dsp.SpeechBandMasker
import ch.etasystems.pirol.audio.export.ExcerptWav
import ch.etasystems.pirol.audio.export.FrameSpan
import ch.etasystems.pirol.audio.export.HumanWindowCollector
import ch.etasystems.pirol.audio.export.HumanWindowLine
import ch.etasystems.pirol.audio.export.HumanWindows
import ch.etasystems.pirol.audio.export.MaskedWavStream
import ch.etasystems.pirol.audio.export.SegmentedRecording
import ch.etasystems.pirol.audio.export.TimeSpan
import ch.etasystems.pirol.data.AppPreferences
import ch.etasystems.pirol.data.repository.ExportEntry
import ch.etasystems.pirol.data.repository.SessionManager
import ch.etasystems.pirol.ml.AudioClassifier
import ch.etasystems.pirol.ml.HumanLabels
import ch.etasystems.pirol.ml.InferenceWorker
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
 */
class HumanExportMask(
    private val sessionManager: SessionManager,
    private val classifier: AudioClassifier,
    private val prefs: AppPreferences
) {
    /** Bewerten nicht moeglich (Modell fehlt oder kennt keine Menschen) */
    class UnavailableException : Exception("Menschen koennen mit dem aktiven Modell nicht erkannt werden")

    /** Vorbereiteter Export: Aufnahme + auszublendende Abschnitte (Session-Frames) */
    class Plan(val recording: SegmentedRecording, val sections: List<FrameSpan>) {
        val isEmpty: Boolean get() = sections.isEmpty()

        fun masker(): SpeechBandMasker =
            SpeechBandMasker(recording.sampleRate, sections, recording.totalFrames, readerOf(recording))
    }

    val enabled: Boolean get() = prefs.exportMaskHumans

    /**
     * Plant das Ausblenden fuer eine Session. null = ausgeschaltet oder keine Aufnahme.
     * @param range nur dieser Bereich wird gebraucht (Ausschnitt); null = ganze Aufnahme
     * @param onProgress Fortschritt des Nachscannens 0..1 (nur wenn gescannt werden muss)
     * @throws UnavailableException wenn gescannt werden muesste, das aktive Modell es aber nicht kann
     */
    suspend fun prepare(
        sessionDir: File,
        range: TimeSpan? = null,
        onProgress: (Float) -> Unit = {},
        recordingIn: SegmentedRecording? = null
    ): Plan? {
        if (!enabled) return null
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
            val scanned = scan(recording, gaps, onProgress)
            val gapSec = gaps.sumOf { it.durationSec.toDouble() }
            Log.i(TAG, "T261_SCAN session=${sessionDir.name} gapSec=${"%.1f".format(gapSec)} windows=${scanned.count { it.kind == HumanWindows.KIND_HUMAN }} ms=${System.currentTimeMillis() - t0}")
            lines += scanned
            try {
                sessionManager.appendHumanWindowLinesTo(sessionDir, HumanWindows.encode(scanned))
            } catch (e: Exception) {
                Log.w(TAG, "Merkliste nicht erweitert (Export laeuft trotzdem)", e)
            }
        }
        val sections = HumanWindows.sections(lines, prefs.exportHumanThreshold, total)
        Log.i(TAG, "T261_PLAN session=${sessionDir.name} sections=${sections.size} maskedSec=${"%.1f".format(sections.sumOf { it.durationSec.toDouble() })}")
        return Plan(recording, HumanWindows.toFrames(sections, recording.sampleRate, recording.totalFrames))
    }

    /** Ersetzt die WAV-Segmente in [entries] durch bearbeitete Kopien (Stream); andere Dateien unveraendert. */
    fun wrap(entries: List<ExportEntry>, plan: Plan?): List<ExportEntry> {
        if (plan == null || plan.isEmpty) return entries
        val masker = plan.masker()
        return entries.map { e ->
            if (e.path.contains('/') || SegmentedRecording.segmentIndex(e.path) == null) return@map e
            val part = plan.recording.parts.firstOrNull { it.source.name == e.path } ?: return@map e
            if (!masker.affects(part.startFrame, part.frames.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())) return@map e
            ExportEntry(e.path) { MaskedWavStream(e.open(), part.startFrame, masker) }
        }
    }

    /** Bearbeitet einen Ausschnitt ab Session-Frame [startFrame] (Kopie; [pcm] bleibt unveraendert). */
    fun maskPcm(pcm: ExcerptWav.Pcm, startFrame: Long, plan: Plan?): ExcerptWav.Pcm {
        if (plan == null || plan.isEmpty || pcm.sampleRate != plan.recording.sampleRate) return pcm
        val masker = plan.masker()
        if (!masker.affects(startFrame, pcm.samples.size)) return pcm
        val copy = pcm.samples.copyOf()
        masker.process(startFrame, copy)
        return ExcerptWav.Pcm(copy, pcm.sampleRate)
    }

    /** Bewertet die [gaps] mit dem aktiven Modell (wie live: Resampling + DSP, dann Rohscores). */
    private suspend fun scan(recording: SegmentedRecording, gaps: List<TimeSpan>, onProgress: (Float) -> Unit): List<HumanWindowLine> =
        withContext(Dispatchers.Default) {
            if (!classifier.isModelAvailable()) throw UnavailableException()
            val sr = recording.sampleRate
            val windowSec = classifier.windowMs / 1000f
            val windowFrames = (windowSec.toDouble() * sr).toLong()
            val starts = gaps.flatMap { g -> HumanWindows.scanStarts(g, windowSec) }
            val collector = HumanWindowCollector(classifier.modelId)
            val highpass = prefs.loadInferenceConfig().highpassEnabled
            val targetRate = classifier.inputSampleRate
            var indices: IntArray? = null
            var namesRef: List<String>? = null
            for ((i, startSec) in starts.withIndex()) {
                currentCoroutineContext().ensureActive()
                val from = (startSec.toDouble() * sr).toLong()
                val pcm = recording.readFrames(from, from + windowFrames) ?: continue
                val input = InferenceWorker.prepareInput(pcm.samples, sr, targetRate, AudioResampler.defaultMode, highpass)
                val table = classifier.scoreAll(input, targetRate) ?: throw UnavailableException()
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
