package ch.etasystems.pirol.data.repository

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Schreibt 16-bit PCM Mono WAV Dateien.
 * Minimaler Writer — kein Streaming, schreibt kompletten Buffer auf einmal.
 */
object WavWriter {

    /**
     * Schreibt Audio-Samples als WAV-Datei.
     *
     * @param file Zieldatei
     * @param samples Audio-Daten als ShortArray (16-bit PCM)
     * @param sampleRate Sample Rate in Hz
     */
    fun write(file: File, samples: ShortArray, sampleRate: Int) {
        val numChannels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * numChannels * bitsPerSample / 8
        val blockAlign = numChannels * bitsPerSample / 8
        val dataSize = samples.size * 2  // 2 Bytes pro Sample
        val fileSize = 36 + dataSize     // Header (44) - 8 (RIFF + Size)

        FileOutputStream(file).use { fos ->
            val header = ByteBuffer.allocate(44)
            header.order(ByteOrder.LITTLE_ENDIAN)

            // RIFF Header
            header.put("RIFF".toByteArray())
            header.putInt(fileSize)
            header.put("WAVE".toByteArray())

            // fmt Chunk
            header.put("fmt ".toByteArray())
            header.putInt(16)              // Chunk Size
            header.putShort(1)             // PCM Format
            header.putShort(numChannels.toShort())
            header.putInt(sampleRate)
            header.putInt(byteRate)
            header.putShort(blockAlign.toShort())
            header.putShort(bitsPerSample.toShort())

            // data Chunk
            header.put("data".toByteArray())
            header.putInt(dataSize)

            fos.write(header.array())

            // Sample-Daten schreiben (Little Endian)
            val sampleBuf = ByteBuffer.allocate(dataSize)
            sampleBuf.order(ByteOrder.LITTLE_ENDIAN)
            for (s in samples) {
                sampleBuf.putShort(s)
            }
            fos.write(sampleBuf.array())
        }
    }
}

/**
 * Streaming-WAV-Writer: Header beim Open mit Platzhaltern, Samples per append(),
 * beim close() werden RIFF-fileSize und data-dataSize im Header aktualisiert.
 *
 * 16-bit PCM Mono. Nicht thread-safe — Aufrufer muss serialisieren.
 */
class StreamingWavWriter(
    private val file: File,
    private val sampleRate: Int
) {
    private var raf: RandomAccessFile? = null
    private var dataBytesWritten: Long = 0L
    private val numChannels = 1
    private val bitsPerSample = 16

    /** Oeffnet die Datei und schreibt einen 44-Byte-Header mit Platzhaltern. */
    fun open() {
        raf = RandomAccessFile(file, "rw")
        val byteRate = sampleRate * numChannels * bitsPerSample / 8
        val blockAlign = numChannels * bitsPerSample / 8
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(36)           // Platzhalter fileSize = 36 + 0
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)
        header.putShort(1)
        header.putShort(numChannels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(blockAlign.toShort())
        header.putShort(bitsPerSample.toShort())
        header.put("data".toByteArray())
        header.putInt(0)            // Platzhalter dataSize = 0
        raf!!.write(header.array())
        dataBytesWritten = 0L
    }

    /** Haengt Samples an. open() muss vorher aufgerufen worden sein. */
    fun append(samples: ShortArray) {
        val r = raf ?: return
        val buf = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) buf.putShort(s)
        r.write(buf.array())
        dataBytesWritten += samples.size * 2L
    }

    /** Aktualisiert RIFF-fileSize (Offset 4) und data-dataSize (Offset 40), schliesst Datei. */
    fun close() {
        val r = raf ?: return
        // RIFF fileSize @ Offset 4 (Little Endian)
        r.seek(4)
        r.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            .putInt((36 + dataBytesWritten).toInt()).array())
        // data dataSize @ Offset 40 (Little Endian)
        r.seek(40)
        r.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(dataBytesWritten.toInt()).array())
        r.close()
        raf = null
    }

    /** Aktuell geschriebene Sample-Anzahl (fuer Logging). */
    val sampleCount: Long get() = dataBytesWritten / 2
}
