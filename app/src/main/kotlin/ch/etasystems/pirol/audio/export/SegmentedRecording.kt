package ch.etasystems.pirol.audio.export

import ch.etasystems.pirol.ml.DetectionResult
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * T130: Aufnahme einer Session als Folge von WAV-Segmenten (T57: recording.wav, recording-002.wav, ...).
 *
 * Bildet eine Session-relative Zeit auf (Segment-Datei, Offset) ab und liest Bereiche, die eine
 * Segmentgrenze ueberspannen, zusammenhaengend. Segment-Starts werden aus den Dateilaengen
 * berechnet (sample-genau, robust gegen nicht finalisierte Header und fehlende session.json-Liste
 * nach einem Absturz). Reines java.io (unit-testbar).
 *
 * Zeitbasis: [DetectionResult.chunkStartSec] ist ab T130 Session-relativ. Aeltere Sessions mit
 * mehreren Segmenten speicherten den Offset im jeweiligen Segment ([legacyLocalOffsets]);
 * [sessionRelative] rechnet diese anhand der Wanduhr auf die Session-Zeitachse um.
 */
class SegmentedRecording(
    val parts: List<Part>,
    val sampleRate: Int,
    /** true = Detektions-Offsets der Session sind segment-lokal (vor T130, nur relevant bei > 1 Segment) */
    val legacyLocalOffsets: Boolean = false,
    /** Wanduhr des Session-Starts (ms), fuer die Legacy-Umrechnung; null = unbekannt */
    val startedAtEpochMs: Long? = null
) {

    /** Ein Segment: Datei, Start-Frame auf der Session-Achse, Anzahl Frames (16-bit Mono). */
    data class Part(val file: File, val startFrame: Long, val frames: Long) {
        val endFrame: Long get() = startFrame + frames
    }

    /** Position innerhalb eines Segments */
    data class Location(val partIndex: Int, val file: File, val offsetSec: Float)

    val totalFrames: Long get() = parts.lastOrNull()?.endFrame ?: 0L

    val durationSec: Float get() = if (sampleRate > 0) totalFrames.toFloat() / sampleRate else 0f

    /** Erste Datei (recording.wav) — fuer Aufrufer, die nur eine Datei kennen. */
    val firstFile: File? get() = parts.firstOrNull()?.file

    /** Laenge der virtuellen, zusammengefuegten WAV (44-Byte-Header + alle Daten). */
    val virtualWavLength: Long get() = HEADER_SIZE + totalFrames * 2L

    /** Session-Zeit [sec] → Segment + lokaler Offset. Ausserhalb wird auf den Rand begrenzt. */
    fun locate(sec: Float): Location? {
        if (parts.isEmpty() || sampleRate <= 0) return null
        val frame = Math.round(sec.toDouble() * sampleRate).coerceIn(0L, totalFrames)
        var idx = parts.indexOfFirst { frame < it.endFrame }
        if (idx < 0) idx = parts.lastIndex
        val p = parts[idx]
        return Location(idx, p.file, (frame - p.startFrame).toFloat() / sampleRate)
    }

    /** Liest [startSec, endSec) der Session als Mono-Samples, ueber Segmentgrenzen hinweg. */
    fun readRange(startSec: Float, endSec: Float): ExcerptWav.Pcm? {
        if (parts.isEmpty() || sampleRate <= 0) return null
        val startFrame = Math.round(startSec.toDouble() * sampleRate).coerceIn(0L, totalFrames)
        val endFrame = Math.round(endSec.toDouble() * sampleRate).coerceIn(startFrame, totalFrames)
        val n = (endFrame - startFrame).toInt()
        if (n <= 0) return null
        val out = ShortArray(n)
        var written = 0
        for (p in parts) {
            val from = maxOf(startFrame, p.startFrame)
            val to = minOf(endFrame, p.endFrame)
            if (to <= from) continue
            val count = (to - from).toInt()
            val bytes = ByteArray(count * 2)
            RandomAccessFile(p.file, "r").use { raf ->
                raf.seek(HEADER_SIZE + (from - p.startFrame) * 2L)
                raf.readFully(bytes)
            }
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(out, written, count)
            written += count
        }
        return ExcerptWav.Pcm(if (written == n) out else out.copyOf(written), sampleRate)
    }

    /**
     * Oeffnet die Session als EINE virtuelle WAV (gueltiger Header + Daten aller Segmente).
     * skip() springt per Datei-Seek, liest also nicht durch.
     */
    fun openWavStream(): InputStream = VirtualWavStream()

    /**
     * Detektion mit Session-relativen chunkStart/End (Legacy-Offsets umgerechnet).
     * Bei aktuellen Sessions oder nur einem Segment unveraendert.
     */
    fun sessionRelative(d: DetectionResult): DetectionResult {
        if (!legacyLocalOffsets || parts.size < 2 || sampleRate <= 0) return d
        val wallSec = startedAtEpochMs?.let { (d.timestampMs - it) / 1000f } ?: return d
        val base = legacySegmentStartSec(d.chunkStartSec, wallSec, parts.map { it.startFrame.toFloat() / sampleRate })
        if (base == 0f) return d
        return d.copy(chunkStartSec = d.chunkStartSec + base, chunkEndSec = d.chunkEndSec + base)
    }

    /** Ausschnitt einer Detektion auf der Session-Achse (T116-Regeln, begrenzt auf die ganze Session). */
    fun excerptRange(d: DetectionResult, maxSec: Float): DetectionExcerpt.Range =
        DetectionExcerpt.compute(sessionRelative(d), durationSec, maxSec)

    private inner class VirtualWavStream : InputStream() {
        private val header = ExcerptWav.header(
            totalFrames.coerceAtMost((Int.MAX_VALUE - HEADER_SIZE) / 2L).toInt(), sampleRate
        )
        private var pos = 0L                     // Position im virtuellen Stream
        private var partIdx = -1
        private var current: RandomAccessFile? = null

        override fun read(): Int {
            val b = ByteArray(1)
            return if (read(b, 0, 1) <= 0) -1 else (b[0].toInt() and 0xFF)
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (pos >= virtualWavLength) return -1
            if (pos < HEADER_SIZE) {
                val n = minOf(len.toLong(), HEADER_SIZE - pos).toInt()
                System.arraycopy(header, pos.toInt(), b, off, n)
                pos += n
                return n
            }
            val frameByte = pos - HEADER_SIZE
            val idx = parts.indexOfFirst { frameByte < it.endFrame * 2L }
            if (idx < 0) return -1
            val p = parts[idx]
            if (idx != partIdx) {
                current?.close()
                current = RandomAccessFile(p.file, "r")
                partIdx = idx
            }
            val raf = current!!
            val local = frameByte - p.startFrame * 2L
            val n = minOf(len.toLong(), p.frames * 2L - local).toInt()
            raf.seek(HEADER_SIZE + local)
            val r = raf.read(b, off, n)
            if (r <= 0) return -1
            pos += r
            return r
        }

        override fun skip(n: Long): Long {
            if (n <= 0) return 0
            val target = minOf(virtualWavLength, pos + n)
            val skipped = target - pos
            pos = target
            return skipped
        }

        override fun available(): Int = (virtualWavLength - pos).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

        override fun close() {
            current?.close()
            current = null
        }
    }

    companion object {
        const val HEADER_SIZE = ExcerptWav.HEADER_SIZE.toLong()

        private val SEGMENT_REGEX = Regex("""recording-(\d{3,})\.wav""")

        /** Segment-Index aus Dateiname: recording.wav = 0, recording-002.wav = 1, ...; null = kein Segment. */
        fun segmentIndex(name: String): Int? = when {
            name == "recording.wav" -> 0
            else -> SEGMENT_REGEX.matchEntire(name)?.groupValues?.get(1)?.toIntOrNull()?.minus(1)
        }

        /** Segment-Dateien einer Session in Aufnahme-Reihenfolge (flach; Legacy audio/recording.wav als Einzeldatei). */
        fun segmentFiles(sessionDir: File): List<File> {
            val flat = sessionDir.listFiles { f -> f.isFile && segmentIndex(f.name) != null }
                ?.sortedBy { segmentIndex(it.name) } ?: emptyList()
            if (flat.isNotEmpty()) return flat
            val legacy = File(sessionDir, "audio/recording.wav")
            return if (legacy.isFile) listOf(legacy) else emptyList()
        }

        /** Datenframes einer PIROL-WAV aus der Dateilaenge (16-bit Mono, 44-Byte-Header). */
        fun framesOf(file: File): Long = ((file.length() - HEADER_SIZE) / 2L).coerceAtLeast(0L)

        /** Abtastrate aus dem WAV-Header (Offset 24); 0 = nicht lesbar. */
        fun sampleRateOf(file: File): Int = try {
            val h = ByteArray(ExcerptWav.HEADER_SIZE)
            val read = file.inputStream().use { it.read(h) }
            if (read < ExcerptWav.HEADER_SIZE) 0
            else ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN).getInt(24).coerceAtLeast(0)
        } catch (_: Exception) { 0 }

        /** Baut die Segmentfolge aus Dateien; Start jedes Segments = Summe der vorherigen Laengen. */
        fun fromFiles(
            files: List<File>,
            sampleRate: Int = files.firstOrNull()?.let { sampleRateOf(it) } ?: 0,
            legacyLocalOffsets: Boolean = false,
            startedAtEpochMs: Long? = null
        ): SegmentedRecording {
            var start = 0L
            val parts = files.map { f ->
                val frames = framesOf(f)
                Part(f, start, frames).also { start += frames }
            }
            return SegmentedRecording(parts, sampleRate, legacyLocalOffsets, startedAtEpochMs)
        }

        /**
         * Aufnahme einer Session (alle Segmente). null = keine Aufnahme vorhanden.
         * @param legacyLocalOffsets aus session.json (fehlendes T130-Flag bei mehreren Segmenten)
         */
        fun forSession(
            sessionDir: File,
            legacyLocalOffsets: Boolean = false,
            startedAtEpochMs: Long? = null
        ): SegmentedRecording? {
            val files = segmentFiles(sessionDir)
            if (files.isEmpty()) return null
            return fromFiles(files, legacyLocalOffsets = legacyLocalOffsets, startedAtEpochMs = startedAtEpochMs)
        }

        /** Wie [forSession], ausgehend von recording.wav (oder einer anderen Segment-Datei) der Session. */
        fun forRecordingFile(recordingFile: File): SegmentedRecording {
            val dir = recordingFile.parentFile
            if (dir == null || segmentIndex(recordingFile.name) == null) return fromFiles(listOf(recordingFile))
            return forSession(dir) ?: fromFiles(listOf(recordingFile))
        }

        /**
         * Legacy (vor T130): Detektions-Offsets waren segment-lokal. Waehlt das Segment, dessen
         * Start + lokaler Offset am naechsten an der Wanduhr-Zeit seit Session-Start liegt
         * (Fehler durch Preroll << Segmentlaenge >= 10 min). Liefert den Segment-Start (s).
         */
        fun legacySegmentStartSec(localSec: Float, wallSecSinceStart: Float, segmentStartsSec: List<Float>): Float =
            segmentStartsSec.minByOrNull { kotlin.math.abs(it + localSec - wallSecSinceStart) } ?: 0f
    }
}
