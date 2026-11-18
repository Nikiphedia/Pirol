package ch.etasystems.pirol.audio.export

import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * T131: Ein WAV-Segment einer Session — entweder eine Datei (File-Modus) oder eine
 * content://-URI (SAF-Modus). Abstrahiert wahlfreien Lesezugriff, damit [SegmentedRecording]
 * fuer beide Speicherarten gleich arbeitet. Pure JVM (Android-Implementierung: UriSegment).
 */
interface SegmentSource {
    val name: String
    /** Dateigroesse in Bytes (inkl. 44-Byte-Header) */
    fun length(): Long
    /** Liest exakt dst.size Bytes ab Byte-Position [pos]; wirft bei Dateiende */
    fun readFully(pos: Long, dst: ByteArray)
    /** Oeffnet einen Stream, der bei Byte-Position [pos] beginnt */
    fun openStreamAt(pos: Long): InputStream
    /** Datei, falls File-Segment */
    val file: File? get() = null
    /** content://-URI als String, falls SAF-Segment */
    val uriString: String? get() = null

    /** Abtastrate aus dem WAV-Header (Offset 24); 0 = nicht lesbar */
    fun sampleRate(): Int = try {
        val h = ByteArray(ExcerptWav.HEADER_SIZE)
        readFully(0L, h)
        ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN).getInt(24).coerceAtLeast(0)
    } catch (_: Exception) { 0 }
}

/** File-Segment (File-Modus) */
class FileSegment(override val file: File) : SegmentSource {
    override val name: String get() = file.name
    override fun length(): Long = file.length()
    override fun readFully(pos: Long, dst: ByteArray) {
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(pos)
            raf.readFully(dst)
        }
    }
    override fun openStreamAt(pos: Long): InputStream {
        val raf = RandomAccessFile(file, "r")
        raf.seek(pos)
        return object : InputStream() {
            override fun read(): Int = raf.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int = raf.read(b, off, len)
            override fun close() = raf.close()
        }
    }
}
