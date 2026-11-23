package ch.etasystems.pirol.data.repository

import android.content.Context
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * T255: Unsichtbarer Arbeitsordner fuer einen Import (`.import-<id>` neben den Sessions, gleicher Speicherort).
 * Erst [commit] benennt ihn in die Session-ID um; vorher erscheint nichts in der Analyse ([SessionLocator]
 * blendet den Praefix aus). [discard] loescht ihn (Abbruch/Fehler); Reste nach einem Kill raeumt der naechste
 * Import weg ([SessionLocator.removeStaging]). So entstehen nie halbe Sessions.
 */
interface ImportStaging {
    /** Dateizugriff (session.json, Zusatzdatei zusammenfuehren) */
    val files: SessionFiles
    /** Datei (auch "memos/x.wav") zum Schreiben oeffnen; Unterordner werden angelegt */
    fun openOutput(relPath: String): OutputStream
    /** WAV-Writer fuer ein Segment (16-bit Mono, Header wie live) */
    fun openWav(name: String, sampleRate: Int): StreamingWavWriter
    /**
     * In die endgueltige Session-ID umbenennen und in den Tagesordner [daySubdir] verschieben (null = Basis).
     * Scheitert nur das Verschieben (SAF-Provider ohne Move), bleibt die fertige Session in der Basis (gueltig).
     * @return Session-Schluessel wie in [SessionManager.listSessions]
     */
    fun commit(finalName: String, daySubdir: String?): File
    fun discard()

    companion object {
        const val PREFIX = ".import-"
    }
}

/** File-Modus */
internal class FileImportStaging(private val base: File, private val dir: File) : ImportStaging {
    override val files: SessionFiles get() = FileSessionFiles(dir)

    override fun openOutput(relPath: String): OutputStream {
        val f = File(dir, relPath)
        f.parentFile?.mkdirs()
        return FileOutputStream(f)
    }

    override fun openWav(name: String, sampleRate: Int): StreamingWavWriter =
        StreamingWavWriter.forFile(File(dir, name), sampleRate)

    override fun commit(finalName: String, daySubdir: String?): File {
        val parent = if (daySubdir != null) File(base, daySubdir).also { it.mkdirs() } else base
        val target = File(parent, finalName)
        if (target.exists()) throw IllegalStateException("Session-Ordner existiert bereits: $finalName")
        if (!dir.renameTo(target)) throw IllegalStateException("Umbenennen fehlgeschlagen: ${dir.name} -> $finalName")
        return target
    }

    override fun discard() { dir.deleteRecursively() }
}

/** SAF-Modus (Ordner per DocumentFile, Umbenennen ueber DocumentsContract) */
internal class DocImportStaging(
    private val context: Context,
    private val base: DocumentFile,
    private val doc: DocumentFile
) : ImportStaging {

    override val files: SessionFiles get() = DocSessionFiles(context, doc)

    /** Datei anlegen; der Provider darf den Namen nicht aendern (sonst falsch benannte Session-Dateien). */
    private fun create(dirDoc: DocumentFile, name: String): DocumentFile {
        dirDoc.findFile(name)?.delete()
        val f = dirDoc.createFile("application/octet-stream", name)
            ?: throw IllegalStateException("SAF: $name konnte nicht erstellt werden")
        if (f.name != name) {
            f.delete()
            throw IllegalStateException("SAF: $name wurde als ${f.name} erstellt")
        }
        return f
    }

    override fun openOutput(relPath: String): OutputStream {
        val parts = relPath.split('/')
        var d = doc
        for (sub in parts.dropLast(1)) d = d.findOrCreateDir(sub) ?: throw IllegalStateException("SAF: Ordner $sub")
        val f = create(d, parts.last())
        return context.contentResolver.openOutputStream(f.uri, "w")
            ?: throw IllegalStateException("SAF: $relPath nicht beschreibbar")
    }

    override fun openWav(name: String, sampleRate: Int): StreamingWavWriter =
        StreamingWavWriter.forSaf(context, create(doc, name).uri, sampleRate)

    override fun commit(finalName: String, daySubdir: String?): File {
        val dayDoc = daySubdir?.let { base.findOrCreateDir(it) }
        if (base.findFile(finalName) != null || dayDoc?.findFile(finalName) != null) {
            throw IllegalStateException("Session-Ordner existiert bereits: $finalName")
        }
        if (!doc.renameTo(finalName) || doc.name != finalName) {
            throw IllegalStateException("SAF: Umbenennen fehlgeschlagen -> $finalName")
        }
        if (dayDoc == null) return SessionFilesLogic.safKey(null, finalName)
        val moved = try {
            DocumentsContract.moveDocument(context.contentResolver, doc.uri, base.uri, dayDoc.uri)
        } catch (e: Exception) {
            Log.w("ImportStaging", "SAF: Verschieben in $daySubdir nicht moeglich, Session bleibt in der Basis", e)
            null
        }
        return SessionFilesLogic.safKey(if (moved != null) daySubdir else null, finalName)
    }

    override fun discard() { doc.delete() }
}
