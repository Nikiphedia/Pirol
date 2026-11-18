package ch.etasystems.pirol.audio.export

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * T116: PCM-Bereich aus recording.wav lesen und als eigenstaendige WAV schreiben.
 * Reines java.io (unit-testbar).
 */
object ExcerptWav {

    const val HEADER_SIZE = 44

    /** Mono-16-bit-PCM-Ausschnitt */
    class Pcm(val samples: ShortArray, val sampleRate: Int)

    /** Dauer in Sekunden aus Dateigroesse (robust gegen nicht finalisierte Header) und Header. */
    fun durationSec(file: File): Float {
        val header = ByteArray(HEADER_SIZE)
        val read = file.inputStream().use { it.read(header) }
        if (read < HEADER_SIZE) return 0f
        val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val rate = buf.getInt(24)
        if (rate <= 0) return 0f
        val fileSamples = ((file.length() - HEADER_SIZE) / 2L).coerceAtLeast(0L)
        val headerSamples = buf.getInt(40).toLong().coerceAtLeast(0L) / 2L
        val total = if (headerSamples in 1..fileSamples) headerSamples else fileSamples
        return total.toFloat() / rate
    }

    /** Liest [startSec, endSec) als Mono-Samples (nur 16-bit Mono-WAV wie von PIROL geschrieben). */
    fun readRange(file: File, startSec: Float, endSec: Float): Pcm? {
        RandomAccessFile(file, "r").use { raf ->
            val header = ByteArray(HEADER_SIZE)
            if (raf.read(header) < HEADER_SIZE) return null
            val hb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val rate = hb.getInt(24)
            if (rate <= 0) return null
            val totalFrames = ((raf.length() - HEADER_SIZE) / 2L).coerceAtLeast(0L)
            val startFrame = (startSec * rate).toLong().coerceIn(0L, totalFrames)
            val endFrame = (endSec * rate).toLong().coerceIn(startFrame, totalFrames)
            val n = (endFrame - startFrame).toInt()
            if (n <= 0) return null
            val bytes = ByteArray(n * 2)
            raf.seek(HEADER_SIZE + startFrame * 2)
            raf.readFully(bytes)
            val samples = ShortArray(n)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
            return Pcm(samples, rate)
        }
    }

    /** 44-Byte-RIFF-Header fuer 16-bit PCM Mono. */
    fun header(numSamples: Int, sampleRate: Int): ByteArray {
        val dataBytes = numSamples * 2
        return ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + dataBytes)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1)                    // PCM
            putShort(1)                    // Mono
            putInt(sampleRate)
            putInt(sampleRate * 2)         // Byte-Rate
            putShort(2)                    // Block-Align
            putShort(16)                   // Bits
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataBytes)
        }.array()
    }

    /** Schreibt [pcm] als vollstaendige WAV-Datei. */
    fun write(target: File, pcm: Pcm) {
        target.outputStream().buffered().use { out ->
            out.write(header(pcm.samples.size, pcm.sampleRate))
            val buf = ByteBuffer.allocate(pcm.samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            buf.asShortBuffer().put(pcm.samples)
            out.write(buf.array())
        }
    }
}
