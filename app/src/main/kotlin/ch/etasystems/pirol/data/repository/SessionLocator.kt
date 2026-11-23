package ch.etasystems.pirol.data.repository

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import ch.etasystems.pirol.data.AppPreferences
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * T251 (N7): Speicherort aufloesen, Sessions listen und finden (aus SessionManager herausgeloest).
 *
 * SAF-Sessions werden im Analyse-Tab ueber einen synthetischen File-Schluessel (/saf/...) identifiziert (T131);
 * [safSessions] bildet den Schluessel auf das DocumentFile ab. Session-Ordnername = Session-ID (T155).
 */
internal class SessionLocator(
    private val context: Context,
    private val appPreferences: AppPreferences
) {
    private val safSessions = java.util.concurrent.ConcurrentHashMap<String, DocumentFile>()

    /** T251 (M5): Session-ID (= Ordnername) -> Schluessel aus dem letzten Listing */
    private val byId = java.util.concurrent.ConcurrentHashMap<String, File>()

    // ── Storage-Aufloesung ────────────────────────────────────────────

    /** Basis-Verzeichnis (File-Modus); bei SAF der Dateipfad der Tree-URI (nur primaerer Speicher). */
    fun resolveBaseFile(): File {
        val safUri = appPreferences.storageBaseUri
        if (safUri != null) {
            val resolved = safUriToFile(Uri.parse(safUri))
            if (resolved != null) return resolved
        }
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, PIROL_DIR)
    }

    /** DocumentFile-Basis fuer SAF-Schreiboperationen; null ohne storageBaseUri. */
    fun resolveBaseDocFile(): DocumentFile? {
        val safUri = appPreferences.storageBaseUri ?: return null
        return DocumentFile.fromTreeUri(context, Uri.parse(safUri))
    }

    /** SAF-Tree-URI als Dateipfad (nur primaerer Speicher); null bei SD-Karte oder unbekanntem Schema. */
    private fun safUriToFile(uri: Uri): File? = try {
        val parts = DocumentsContract.getTreeDocumentId(uri).split(":")
        if (parts.size >= 2 && parts[0].equals("primary", ignoreCase = true))
            File(Environment.getExternalStorageDirectory(), parts[1])
        else null  // SD-Karte: kein direkter Pfad moeglich
    } catch (e: Exception) {
        Log.w(TAG, "SAF URI → File-Pfad fehlgeschlagen: $uri", e)
        null
    }

    /** Tages-Unterordner (YYYY-MM-DD, Lokalzeit) oder null wenn deaktiviert. */
    fun dailySubdirName(): String? =
        if (!appPreferences.storageDailySubfolder) null
        else LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)

    fun defaultBaseDir(): File = File(context.getExternalFilesDir(null) ?: context.filesDir, PIROL_DIR)

    // ── Dokument-IDs (T181) ───────────────────────────────────────────

    private fun fileToDocId(file: File): String? {
        val root = Environment.getExternalStorageDirectory().absolutePath.trimEnd('/', '\\')
        val path = file.absolutePath.replace('\\', '/')
        if (path != root && !path.startsWith("$root/")) return null
        return "primary:" + path.removePrefix(root).trimStart('/')
    }

    fun baseDocumentIds(): List<String> {
        val ids = mutableListOf<String>()
        appPreferences.storageBaseUri?.let { u ->
            runCatching { DocumentsContract.getTreeDocumentId(Uri.parse(u)) }.getOrNull()?.let { ids += it }
        }
        fileToDocId(resolveBaseFile())?.let { ids += it }
        return ids
    }

    fun sessionDocumentId(sessionDir: File, doc: DocumentFile?): String? {
        doc?.let { d -> runCatching { DocumentsContract.getDocumentId(d.uri) }.getOrNull()?.let { return it } }
        return fileToDocId(sessionDir)
    }

    // ── SAF-Zuordnung ─────────────────────────────────────────────────

    /** DocumentFile einer gelisteten SAF-Session; sonst null. */
    fun listedDoc(sessionDir: File): DocumentFile? = safSessions[sessionDir.path]

    fun forget(sessionDir: File) {
        safSessions.remove(sessionDir.path)
        byId.remove(sessionDir.name)
    }

    /** Leitet einen SAF-Session-Ordner auf einen Dateipfad ab (primaerer Speicher), nur fuer den Auto-Export. */
    fun resolveSessionDirFromDoc(sessionDoc: DocumentFile): File? {
        val safUri = appPreferences.storageBaseUri ?: return null
        val baseFile = safUriToFile(Uri.parse(safUri)) ?: return null
        val sessionName = sessionDoc.name ?: return null
        val candidates = if (appPreferences.storageDailySubfolder) {
            baseFile.listFiles()?.filter { it.isDirectory }?.map { File(it, sessionName) } ?: emptyList()
        } else listOf(File(baseFile, sessionName))
        return candidates.firstOrNull { it.exists() }
    }

    // ── Listen ────────────────────────────────────────────────────────

    /** Alle Session-Ordner (neueste zuerst): SAF-Basis (Vorrang) und Datei-Basis, mit/ohne Tagesordner. */
    fun listSessions(): List<File> {
        val results = mutableListOf<File>()
        val safNames = mutableSetOf<String>()
        listSafSessions().forEach { (key, doc) ->
            safSessions[key.path] = doc
            safNames += key.name
            results += key
        }
        val base = resolveBaseFile()
        if (base.exists()) {
            val children = base.listFiles()?.toList() ?: emptyList()
            if (appPreferences.storageDailySubfolder || children.any { it.isDirectory && looksLikeDateDir(it.name) }) {
                children.filter { it.isDirectory }.forEach { dayDir ->
                    dayDir.listFiles()?.filter { it.isDirectory }?.let { results.addAll(it) }
                }
            }
            results.addAll(children.filter { it.isDirectory && !looksLikeDateDir(it.name) })
        }
        // Duplikate: File-Sessions, die schon als SAF-Session gelistet sind, fallen weg
        val (safKeys, fileSessions) = results.partition { safSessions.containsKey(it.path) }
        val out = (safKeys + SessionFilesLogic.dropListedNames(fileSessions, safNames))
            .distinctBy { it.absolutePath }
            .sortedByDescending { it.name }
        byId.clear()
        out.forEach { byId.putIfAbsent(it.name, it) }
        return out
    }

    private fun listSafSessions(): List<Pair<File, DocumentFile>> {
        if (appPreferences.storageBaseUri == null) return emptyList()
        val base = try { resolveBaseDocFile() } catch (_: Exception) { null } ?: return emptyList()
        if (!base.canRead()) return emptyList()
        val out = mutableListOf<Pair<File, DocumentFile>>()
        try {
            for (child in base.listFiles()) {
                val childName = child.name ?: continue
                if (!child.isDirectory) continue
                if (looksLikeDateDir(childName)) {
                    for (s in child.listFiles()) {
                        val n = s.name ?: continue
                        if (s.isDirectory) out += SessionFilesLogic.safKey(childName, n) to s
                    }
                } else {
                    out += SessionFilesLogic.safKey(null, childName) to child
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "SAF-Sessions listen fehlgeschlagen", e)
        }
        return out
    }

    /**
     * T251 (M5): Session per ID finden, ohne alle session.json zu lesen: Ordnername = Session-ID.
     * Zuerst der Index aus dem letzten Listing, sonst einmal neu listen.
     */
    fun findBySessionId(sessionId: String): File? {
        byId[sessionId]?.let { return it }
        listSessions()
        return byId[sessionId]
    }

    /** Leere Tagesordner (SAF und File) entfernen (T180). */
    fun removeEmptyDayDirs() {
        try {
            if (appPreferences.storageBaseUri != null) {
                resolveBaseDocFile()?.takeIf { it.canRead() }?.listFiles()?.forEach { d ->
                    val n = d.name ?: return@forEach
                    if (d.isDirectory && looksLikeDateDir(n) && d.listFiles().isEmpty()) d.delete()
                }
            }
            resolveBaseFile().listFiles()?.forEach { d ->
                if (d.isDirectory && looksLikeDateDir(d.name) && d.listFiles()?.isEmpty() == true) d.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Leere Tagesordner entfernen fehlgeschlagen", e)
        }
    }

    companion object {
        private const val TAG = "SessionManager"
        const val PIROL_DIR = "PIROL"

        private val DATE_DIR = Regex("\\d{4}-\\d{2}-\\d{2}")
        fun looksLikeDateDir(name: String): Boolean = name.matches(DATE_DIR)
    }
}

/** Unterordner finden oder erstellen (SAF). */
internal fun DocumentFile.findOrCreateDir(name: String): DocumentFile? = findFile(name) ?: createDirectory(name)
