package ch.etasystems.pirol.data.repository

import java.io.File
import java.io.FileOutputStream
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
