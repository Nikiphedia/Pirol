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

    /**
     * T130: true, wenn eine gueltige PIROL-WAV in RIFF- oder data-Groesse nicht zur Dateilaenge passt
     * (Prozess-Kill vor close(): Groessen stehen auf dem Stand des letzten periodischen Updates).
     */
    fun headerSizesStale(file: File): Boolean {
        if (!file.exists() || file.length() < 44) return false
        RandomAccessFile(file, "r").use { raf ->
            val head = ByteArray(44)
            raf.readFully(head)
            if (String(head, 0, 4, Charsets.US_ASCII) != "RIFF" ||
                String(head, 8, 4, Charsets.US_ASCII) != "WAVE" ||
                String(head, 36, 4, Charsets.US_ASCII) != "data") return false
            val bb = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
            val dataBytes = minOf(raf.length() - 44L, 0xFFFFFFFFL)
            val riff = bb.getInt(4).toLong() and 0xFFFFFFFFL
            val data = bb.getInt(40).toLong() and 0xFFFFFFFFL
            return data != dataBytes || riff != minOf(36L + dataBytes, 0xFFFFFFFFL)
        }
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
 * T130: Alle [SYNC_INTERVAL_SEC] s Audio werden die Header-Groessen nachgefuehrt und force()
 * aufgerufen; nach einem Kill ist die Datei damit ohne Reparatur abspielbar (fehlt hoechstens
 * der Header-Stand der letzten <5 s, die Daten selbst sind vollstaendig).
 */
class StreamingWavWriter private constructor(
    private val channel: FileChannel,
    private val sampleRate: Int,
    private val onClose: (() -> Unit)? = null
) {
    companion object {
        /**
         * T130: Intervall (Sekunden Audio) fuer Header-Update + force(). Vorher alle 20 Appends
         * (= 20-60 s je nach Overlap); nach einem Kill stimmten die Header-Groessen bis dahin nicht.
         */
        const val SYNC_INTERVAL_SEC = 5

        /**
         * T65: Crash-Recovery — finalisiert WAV-Header eines abgebrochen geschriebenen Files.
         * Berechnet data-Groesse aus Datei-Groesse minus 44 Bytes Header und schreibt
         * RIFF-fileSize (Offset 4) + data-dataSize (Offset 40) korrekt.
         * Idempotent: sichere Mehrfach-Aufrufe, prueft Mindest-Groesse vorher.
         *
         * @param file WAV-Datei mit ggf. ungueltigem Header (Crash-Zustand)
         */
        fun finalizeHeader(file: File) {
            val fileSize = file.length()
            if (fileSize < 44) return  // Zu klein fuer gueltigen WAV-Header
            val dataBytes = fileSize - 44L
            RandomAccessFile(file, "rw").use { raf ->
                val ch = raf.channel
                // RIFF fileSize @ Offset 4
                val riffBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                riffBuf.putInt(minOf(36L + dataBytes, 0xFFFFFFFFL).toInt())
                riffBuf.flip()
                ch.write(riffBuf, 4L)
                // data dataSize @ Offset 40
                val dataBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                dataBuf.putInt(minOf(dataBytes, 0xFFFFFFFFL).toInt())
                dataBuf.flip()
                ch.write(dataBuf, 40L)
                ch.force(true)
            }
        }

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
    private var lastSyncBytes: Long = 0L
    private val syncIntervalBytes: Long = sampleRate.toLong() * 2L * SYNC_INTERVAL_SEC
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
        // Relativer Write: verschiebt Channel-Position auf 44, sodass append() korrekt dahinter schreibt.
        // Absolute close()-Writes (Offset 4 + 40 fuer RIFF/data-Groesse) bleiben unveraendert.
        channel.write(header)
        dataBytesWritten = 0L
        lastSyncBytes = 0L
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
        // T130: alle ~5 s Audio Header-Groessen nachfuehren + auf den Datentraeger schreiben.
        // Die Samples selbst liegen nach channel.write() bereits im Kernel (kein Puffer im Prozess),
        // ein Prozess-Kill verliert hier also nichts; force() sichert zusaetzlich gegen Stromausfall.
        if (dataBytesWritten - lastSyncBytes >= syncIntervalBytes) {
            writeSizeFields()
            channel.force(false)
            lastSyncBytes = dataBytesWritten
        }
    }

    /**
     * RIFF-fileSize (Offset 4) und data-dataSize (Offset 40) auf den aktuellen Stand setzen.
     * Absolute channel.write(buf, pos) aendert die Channel-Position nicht → Append-Position bleibt korrekt.
     */
    private fun writeSizeFields() {
        val riffBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        riffBuf.putInt(minOf(36L + dataBytesWritten, 0xFFFFFFFFL).toInt())
        riffBuf.flip()
        while (riffBuf.hasRemaining()) channel.write(riffBuf, 4L + riffBuf.position())

        val dataBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        dataBuf.putInt(minOf(dataBytesWritten, 0xFFFFFFFFL).toInt())
        dataBuf.flip()
        while (dataBuf.hasRemaining()) channel.write(dataBuf, 40L + dataBuf.position())
    }

    /**
     * Aktualisiert RIFF-fileSize (Offset 4) und data-dataSize (Offset 40), schliesst Channel.
     * Nutzt absolute Channel-Writes ohne Positionswechsel (FileChannel.write(buf, pos)).
     */
    fun close() {
        writeSizeFields()
        channel.force(true)
        channel.close()
        onClose?.invoke()
    }

    /** Aktuell geschriebene Sample-Anzahl. */
    val sampleCount: Long get() = dataBytesWritten / 2
}
