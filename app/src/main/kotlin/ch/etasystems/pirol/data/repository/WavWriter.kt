package ch.etasystems.pirol.data.repository

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
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
            // Bulk-Konvertierung (statt putShort() pro Sample)
            sampleBuf.asShortBuffer().put(samples)
            fos.write(sampleBuf.array())
        }
    }

    /**
     * Repariert den Header einer 16-bit-PCM-Mono-WAV, deren erste 44 Bytes ueberschrieben wurden (T61).
     *
     * Hintergrund: StreamingWavWriter hat seit T51 bis T61 das erste append() ab Offset 0 geschrieben
     * und damit den Header mit Audio ueberschrieben (nur Offset 4 und 40 wurden beim close() gepatcht).
     * Das Audio ab Byte 44 ist intakt; die ersten 22 Samples sind verloren.
     *
     * Erkennung: Gueltige Dateien beginnen immer mit "RIFF" und haben "WAVE" an Offset 8.
     * Dateien mit korrektem Header werden nicht angefasst.
     *
     * @return true wenn der Header neu geschrieben wurde
     */
    fun repairHeaderIfNeeded(file: File, sampleRate: Int): Boolean {
        if (!file.exists() || file.length() < 44 || sampleRate <= 0) return false
        RandomAccessFile(file, "rw").use { raf ->
            val head = ByteArray(12)
            raf.readFully(head)
            val riff = String(head, 0, 4, Charsets.US_ASCII)
            val wave = String(head, 8, 4, Charsets.US_ASCII)
            if (riff == "RIFF" && wave == "WAVE") return false

            val dataSize = (raf.length() - 44).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            raf.seek(0)
            raf.write(buildHeader(sampleRate, dataSize))
        }
        return true
    }

    /** 44-Byte PCM-Mono-16-bit-Header */
    private fun buildHeader(sampleRate: Int, dataSize: Int): ByteArray {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(36 + dataSize)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)
        header.putShort(1)
        header.putShort(1)
        header.putInt(sampleRate)
        header.putInt(sampleRate * 2)
        header.putShort(2)
        header.putShort(16)
        header.put("data".toByteArray())
        header.putInt(dataSize)
        return header.array()
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
    // Wiederverwendeter Sample-Buffer fuer append() (waechst bei Bedarf, sonst keine Allokation pro Chunk)
    private var appendBuffer: ByteBuffer = ByteBuffer.allocate(0)
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
        // Absolutes write(buf, pos) veraendert die Channel-Position NICHT → ohne diese Zeile
        // wuerde das erste append() ab Offset 0 schreiben und den Header ueberschreiben.
        channel.position(44L)
        dataBytesWritten = 0L
        appendCount = 0
    }

    /** Haengt Samples an. open() muss vorher aufgerufen worden sein. */
    fun append(samples: ShortArray) {
        val byteCount = samples.size * 2
        if (appendBuffer.capacity() < byteCount) {
            appendBuffer = ByteBuffer.allocate(byteCount).order(ByteOrder.LITTLE_ENDIAN)
        }
        val buf = appendBuffer
        buf.clear()
        // Bulk-Konvertierung (statt putShort() pro Sample); ShortBuffer-View teilt Byte-Order + Daten
        buf.asShortBuffer().put(samples)
        buf.limit(byteCount)
        // FileChannel.write() kann (v.a. bei SAF) weniger Bytes schreiben → bis Ende schreiben
        while (buf.hasRemaining()) channel.write(buf)
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
