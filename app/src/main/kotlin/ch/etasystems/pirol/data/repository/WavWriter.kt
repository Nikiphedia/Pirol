package ch.etasystems.pirol.data.repository

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

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
 *
 * Unterstuetzt sowohl File-basierte als auch SAF-basierte Ausgabe via FileChannel.
 * Periodisches Flush (alle FLUSH_INTERVAL_APPENDS Aufrufe) sichert die Datei
 * bei langen Sessions gegen App-Kills.
 */
class StreamingWavWriter private constructor(
    private val channel: FileChannel,
    private val sampleRate: Int,
    private val onClose: (() -> Unit)? = null
) {
    companion object {
        private const val FLUSH_INTERVAL_APPENDS = 20  // ~60s bei 3s-Chunks

        /**
         * Writer fuer File-basierte Aufnahme (getExternalFilesDir oder interner Speicher).
         */
        fun forFile(file: File, sampleRate: Int): StreamingWavWriter {
            val channel = FileOutputStream(file).channel
            return StreamingWavWriter(channel, sampleRate)
        }

        /**
         * Writer fuer SAF-basierte Aufnahme.
         * @param context Context fuer ContentResolver
         * @param uri DocumentFile-URI der Zieldatei (muss bereits existieren)
         * @param sampleRate Sample Rate in Hz
         */
        fun forSaf(context: Context, uri: Uri, sampleRate: Int): StreamingWavWriter {
            val pfd = context.contentResolver.openFileDescriptor(uri, "rw")
                ?: throw IllegalStateException("SAF-URI nicht beschreibbar: $uri")
            val channel = FileOutputStream(pfd.fileDescriptor).channel
            return StreamingWavWriter(channel, sampleRate, onClose = { pfd.close() })
        }
    }

    private var dataBytesWritten: Long = 0L
    private var appendCount: Int = 0
    private val numChannels = 1
    private val bitsPerSample = 16

    /** Schreibt einen 44-Byte-Header mit Platzhaltern. */
    fun open() {
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
        header.flip()
        channel.write(header, 0L)
        dataBytesWritten = 0L
        appendCount = 0
    }

    /** Haengt Samples an. open() muss vorher aufgerufen worden sein. */
    fun append(samples: ShortArray) {
        val buf = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) buf.putShort(s)
        buf.flip()
        channel.write(buf)
        dataBytesWritten += samples.size * 2L
        appendCount++
        // Periodisches Flush: sichert Datei bei App-Kills in langen Sessions
        if (appendCount % FLUSH_INTERVAL_APPENDS == 0) {
            // T57-B2: Header-Felder aktualisieren damit Windows korrekte Hz + Laenge zeigt
            // Absolute channel.write(buf, pos) aendert die Channel-Position nicht → Append-Position bleibt korrekt
            val riffBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            riffBuf.putInt((36 + dataBytesWritten).toInt())
            riffBuf.flip()
            channel.write(riffBuf, 4L)

            val dataBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            dataBuf.putInt(dataBytesWritten.toInt())
            dataBuf.flip()
            channel.write(dataBuf, 40L)

            channel.force(false)
        }
    }

    /**
     * Aktualisiert RIFF-fileSize (Offset 4) und data-dataSize (Offset 40), schliesst Channel.
     * Nutzt absolute Channel-Writes ohne Positionswechsel (FileChannel.write(buf, pos)).
     */
    fun close() {
        // RIFF fileSize @ Offset 4
        val riffSizeBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        riffSizeBuf.putInt((36 + dataBytesWritten).toInt())
        riffSizeBuf.flip()
        channel.write(riffSizeBuf, 4L)

        // data dataSize @ Offset 40
        val dataSizeBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        dataSizeBuf.putInt(dataBytesWritten.toInt())
        dataSizeBuf.flip()
        channel.write(dataSizeBuf, 40L)

        channel.force(true)
        channel.close()
        onClose?.invoke()
    }

    /** Aktuell geschriebene Sample-Anzahl. */
    val sampleCount: Long get() = dataBytesWritten / 2
}
