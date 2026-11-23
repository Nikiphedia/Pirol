package ch.etasystems.pirol.data.importer

import ch.etasystems.pirol.data.repository.RecordingSegment
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * T255: Verteilt importiertes Audio auf Session-Segmente (recording.wav, recording-002.wav, ...).
 *
 * Regeln (Begruendung im Handover):
 * - Jede Quelldatei beginnt ein neues Segment. Zwischen zwei Dateien liegt meist eine Luecke (AudioMoth-Zyklen);
 *   die Session-Achse (chunkStartSec) bleibt luecken-los aneinandergereiht wie bei live rotierten Sessions,
 *   damit [ch.etasystems.pirol.audio.export.SegmentedRecording] unveraendert bleibt und keine Stille
 *   gespeichert wird. Die echte Wanduhr steht je Segment in [RecordingSegment.startedAt].
 * - Lange Dateien werden wie live nach [maxFramesPerSegment] (Einstellung maxRecordingMinutes) geteilt;
 *   die Wanduhr des Folgesegments = Dateistart + bereits geschriebene Dauer.
 * - Leere Quellen erzeugen kein Segment.
 */
class ImportSegmenter(
    private val sampleRate: Int,
    private val maxFramesPerSegment: Long,
    private val open: (fileName: String) -> FrameSink
) {
    /** Ziel eines Segments (WAV-Writer) */
    interface FrameSink {
        fun append(samples: ShortArray)
        fun close()
    }

    private class Open(val sink: FrameSink, val fileName: String, val startFrame: Long, val wall: ZonedDateTime) {
        var frames = 0L
    }

    private val done = mutableListOf<RecordingSegment>()
    private var current: Open? = null
    private var sourceWall: ZonedDateTime? = null
    private var sourceFrames = 0L

    /** Bisher geschriebene Frames (Session-Achse) */
    var totalFrames = 0L
        private set

    init { require(sampleRate > 0 && maxFramesPerSegment > 0) }

    /** Neue Quelldatei mit Startzeit [wallClockStart]: das naechste Sample beginnt ein neues Segment. */
    fun startSource(wallClockStart: ZonedDateTime) {
        closeCurrent()
        sourceWall = wallClockStart
        sourceFrames = 0L
    }

    fun write(samples: ShortArray) {
        val wall = sourceWall ?: error("startSource fehlt")
        var off = 0
        while (off < samples.size) {
            val seg = current ?: run {
                val name = segmentFileName(done.size)
                val start = wall.plusNanos(sourceFrames * 1_000_000_000L / sampleRate)
                Open(open(name), name, totalFrames, start).also { current = it }
            }
            val n = minOf((samples.size - off).toLong(), maxFramesPerSegment - seg.frames).toInt()
            seg.sink.append(if (off == 0 && n == samples.size) samples else samples.copyOfRange(off, off + n))
            seg.frames += n
            totalFrames += n
            sourceFrames += n
            off += n
            if (seg.frames >= maxFramesPerSegment) closeCurrent()
        }
    }

    /** Letztes Segment schliessen; Segmentliste (Session-Achse + Wanduhr) */
    fun finish(): List<RecordingSegment> {
        closeCurrent()
        return done.toList()
    }

    /** Abbruch: offenes Segment ohne Eintrag schliessen (Fehler ignoriert) */
    fun abort() {
        val c = current ?: return
        current = null
        runCatching { c.sink.close() }
    }

    private fun closeCurrent() {
        val c = current ?: return
        current = null
        c.sink.close()
        done += RecordingSegment(
            fileName = c.fileName,
            startSec = (c.startFrame.toDouble() / sampleRate).toFloat(),
            durationSec = (c.frames.toDouble() / sampleRate).toFloat(),
            startedAt = c.wall.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        )
    }

    companion object {
        /** Wie live (SessionManager.segmentFileName): 0 -> recording.wav, 1 -> recording-002.wav */
        fun segmentFileName(index: Int): String =
            if (index == 0) "recording.wav" else "recording-${String.format(java.util.Locale.ROOT, "%03d", index + 1)}.wav"
    }
}
