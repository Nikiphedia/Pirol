package ch.etasystems.pirol.data.repository

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.documentfile.provider.DocumentFile
import ch.etasystems.pirol.audio.export.SegmentSource
import ch.etasystems.pirol.audio.export.SegmentedRecording
import java.io.FileInputStream
import java.io.InputStream

/**
 * T131: SAF-Modus — Session-Ordner als DocumentFile, Zugriff ueber ContentResolver.
 * Nur auf einem Geraet mit SAF-Speicher pruefbar (kein Unit-Test).
 */
class DocSessionFiles(context: Context, val doc: DocumentFile) : SessionFiles {
    private val resolver: ContentResolver = context.contentResolver

    override val name: String get() = doc.name ?: ""

    override fun exists(fileName: String) = doc.findFile(fileName) != null

    override fun readText(fileName: String): String? {
        val f = doc.findFile(fileName) ?: return null
        return resolver.openInputStream(f.uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    override fun writeReplacing(fileName: String, content: String) {
        val f = doc.findFile(fileName) ?: doc.createFile(mimeOf(fileName), fileName)
            ?: throw IllegalStateException("SAF: $fileName konnte nicht erstellt werden")
        // "wt" = truncate; ein einziger Schreibvorgang mit dem fertigen Inhalt
        resolver.openOutputStream(f.uri, "wt")?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
            ?: throw IllegalStateException("SAF: $fileName nicht beschreibbar")
    }

    override fun append(fileName: String, content: String) {
        val f = doc.findFile(fileName) ?: doc.createFile(mimeOf(fileName), fileName)
            ?: throw IllegalStateException("SAF: $fileName konnte nicht erstellt werden")
        resolver.openOutputStream(f.uri, "wa")?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
            ?: throw IllegalStateException("SAF: $fileName nicht beschreibbar")
    }

    override fun listNames(): List<String> = doc.listFiles().mapNotNull { it.name }

    override fun segmentSources(): List<SegmentSource> {
        val byName = doc.listFiles().filter { it.isFile && it.name != null }.associateBy { it.name!! }
        val ordered = SegmentedRecording.orderedSegmentNames(byName.keys.toList())
        return ordered.map { UriSegment(resolver, byName.getValue(it)) }
    }

    override fun deleteAll(): Boolean = doc.delete()

    override fun delete(fileName: String): Boolean = doc.findFile(fileName)?.delete() ?: true

    private fun mimeOf(fileName: String) = when {
        fileName.endsWith(".json") -> "application/json"
        fileName.endsWith(".wav") -> "audio/wav"
        fileName.endsWith(".txt") -> "text/plain"
        else -> "application/octet-stream"
    }
}

/** SAF-Segment: content://-URI mit wahlfreiem Zugriff (FileDescriptor-Channel, sonst Stream-Skip). */
class UriSegment(
    private val resolver: ContentResolver,
    private val doc: DocumentFile
) : SegmentSource {
    private val uri: Uri get() = doc.uri
    override val name: String get() = doc.name ?: ""
    override val uriString: String get() = uri.toString()

    override fun length(): Long = doc.length()

    override fun readFully(pos: Long, dst: ByteArray) {
        openStreamAt(pos).use { s ->
            var off = 0
            while (off < dst.size) {
                val r = s.read(dst, off, dst.size - off)
                if (r < 0) throw java.io.EOFException("SAF-Segment $name endet vor ${pos + dst.size}")
                off += r
            }
        }
    }

    override fun openStreamAt(pos: Long): InputStream {
        // Bevorzugt: ParcelFileDescriptor mit Seek (ExternalStorageProvider unterstuetzt das)
        try {
            val pfd: ParcelFileDescriptor? = resolver.openFileDescriptor(uri, "r")
            if (pfd != null) {
                val fis = FileInputStream(pfd.fileDescriptor)
                try {
                    fis.channel.position(pos)
                    return object : InputStream() {
                        override fun read(): Int = fis.read()
                        override fun read(b: ByteArray, off: Int, len: Int): Int = fis.read(b, off, len)
                        override fun close() { try { fis.close() } finally { pfd.close() } }
                    }
                } catch (_: Exception) {
                    // Nicht seekbar (Pipe) → Fallback unten
                    try { fis.close() } catch (_: Exception) {}
                    pfd.close()
                }
            }
        } catch (_: Exception) { /* Fallback unten */ }
        val s = resolver.openInputStream(uri) ?: throw java.io.FileNotFoundException(uriString)
        var left = pos
        while (left > 0) {
            val skipped = s.skip(left)
            if (skipped <= 0) {
                if (s.read() < 0) break  // Dateiende
                left -= 1
            } else left -= skipped
        }
        return s
    }
}
