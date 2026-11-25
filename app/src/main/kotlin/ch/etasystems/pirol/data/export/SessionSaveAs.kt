package ch.etasystems.pirol.data.export

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import ch.etasystems.pirol.data.repository.ExportEntry
import ch.etasystems.pirol.data.repository.SessionExportRules
import ch.etasystems.pirol.data.sync.writeZip
import java.io.File
import java.io.IOException

/**
 * T258: "Speichern unter" fuer eine Session: ZIP (identisch zum Upload-ZIP) oder Ordner in ein frei
 * gewaehltes SAF-Ziel. Ausschlussregeln: [SessionExportRules]. Setzt keine Upload-Einstellungen.
 */
class SessionSaveAs(private val context: Context) {

    /** Exportierbare Dateien: SAF-Session ueber [doc], sonst Dateisystem. */
    fun entriesOf(sessionDir: File, doc: DocumentFile?): List<ExportEntry> {
        if (doc == null) return SessionExportRules.entriesOf(sessionDir)
        val out = mutableListOf<ExportEntry>()
        fun walk(d: DocumentFile, prefix: String) {
            for (c in d.listFiles()) {
                val n = c.name ?: continue
                if (c.isDirectory) walk(c, "$prefix$n/")
                else if (!SessionExportRules.isExcluded(n)) {
                    val uri = c.uri
                    out += ExportEntry(prefix + n) {
                        context.contentResolver.openInputStream(uri) ?: throw IOException("Quelle nicht lesbar: $n")
                    }
                }
            }
        }
        walk(doc, "")
        return SessionExportRules.keepReferencedMemos(out, sessionDir.name)  // T303: nur Memos mit Bezug
    }

    /** ZIP erst in den Cache, dann in [target] streamen; Cache-Datei wird immer geloescht. */
    fun saveZip(entries: List<ExportEntry>, detections: String?, target: Uri, cacheName: String) {
        val tmp = File(context.cacheDir, "saveas_${ExportNames.sanitizePart(cacheName, 60).ifEmpty { "session" }}.zip")
        try {
            tmp.outputStream().use { writeZip(it, entries, detections) }
            val out = context.contentResolver.openOutputStream(target, "wt") ?: throw IOException("Ziel nicht schreibbar")
            out.use { o -> tmp.inputStream().use { it.copyTo(o, 64 * 1024) }; o.flush() }
        } finally {
            tmp.delete()
        }
    }

    /**
     * Legt unter [root] einen Unterordner [folderBase] (bei Kollision `-2` usw.) an und kopiert alle
     * [entries] hinein. Liefert den tatsaechlichen Ordnernamen. Bei Fehler wird der Teilordner entfernt.
     */
    fun saveFolder(root: DocumentFile, folderBase: String, entries: List<ExportEntry>, detections: String?): String {
        val existing = root.listFiles().mapNotNull { it.name }.toSet()
        val name = ExportNames.uniqueFolderName(folderBase) { it in existing }
        val dest = root.createDirectory(name) ?: throw IOException("Zielordner nicht erstellbar: $name")
        try {
            val dirs = HashMap<String, DocumentFile>()
            dirs[""] = dest
            for (e in entries) {
                val slash = e.path.lastIndexOf('/')
                val dirPath = if (slash < 0) "" else e.path.substring(0, slash)
                val fileName = e.path.substring(slash + 1)
                val parent = dirFor(dirPath, dirs)
                // Generischer MIME-Typ: verhindert, dass der Provider eine Endung an den Namen haengt
                val out = parent.createFile("application/octet-stream", fileName)
                    ?: throw IOException("Datei nicht erstellbar: $fileName")
                val os = context.contentResolver.openOutputStream(out.uri, "wt")
                    ?: throw IOException("Ziel nicht schreibbar: $fileName")
                os.use { o ->
                    if (detections != null && e.path == SessionExportRules.DETECTIONS_ENTRY) {
                        o.write(detections.toByteArray(Charsets.UTF_8))
                    } else {
                        e.open().use { it.copyTo(o, 256 * 1024) }
                    }
                    o.flush()
                }
            }
            return name
        } catch (t: Throwable) {
            try { dest.delete() } catch (x: Exception) { Log.w(TAG, "Aufraeumen fehlgeschlagen", x) }
            throw t
        }
    }

    private fun dirFor(path: String, cache: HashMap<String, DocumentFile>): DocumentFile {
        cache[path]?.let { return it }
        val slash = path.lastIndexOf('/')
        val parent = dirFor(if (slash < 0) "" else path.substring(0, slash), cache)
        val n = path.substring(slash + 1)
        val d = parent.findFile(n)?.takeIf { it.isDirectory } ?: parent.createDirectory(n)
            ?: throw IOException("Ordner nicht erstellbar: $n")
        cache[path] = d
        return d
    }

    fun targetRoot(treeUri: Uri): DocumentFile? =
        DocumentFile.fromTreeUri(context, treeUri)?.takeIf { it.isDirectory && it.canWrite() }

    private companion object { const val TAG = "SessionSaveAs" }
}
