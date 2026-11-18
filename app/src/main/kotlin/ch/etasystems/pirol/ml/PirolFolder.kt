package ch.etasystems.pirol.ml

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import ch.etasystems.pirol.data.AppPreferences

/**
 * T129: Zugriff auf den vom Benutzer einmalig freigegebenen PIROL-Ordner (ACTION_OPEN_DOCUMENT_TREE).
 * Umgeht Scoped Storage (Android 11+): Dateien, die die App nicht selbst angelegt hat,
 * sind per java.io.File unsichtbar, per persistierter SAF-Berechtigung aber lesbar.
 */
class PirolFolder(private val context: Context, private val prefs: AppPreferences) {

    companion object {
        private const val TAG = "PirolFolder"
        private const val RW = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }

    private fun root(): DocumentFile? {
        val s = prefs.pirolFolderUri ?: return null
        return try {
            DocumentFile.fromTreeUri(context, Uri.parse(s))?.takeIf { it.exists() && it.canRead() }
        } catch (e: Exception) {
            Log.w(TAG, "Ordner nicht zugreifbar: ${e.message}")
            null
        }
    }

    /** Ordner ist freigegeben und aktuell lesbar */
    fun isAvailable(): Boolean = root() != null

    /** Anzeigename des Ordners (null ohne Freigabe) */
    fun displayName(): String? = root()?.name

    /** Freigabe speichern und dauerhaft sichern */
    fun grant(uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(uri, RW)
        } catch (e: Exception) {
            Log.w(TAG, "Persistierbare Berechtigung fehlgeschlagen: ${e.message}")
        }
        prefs.pirolFolderUri = uri.toString()
    }

    /** Freigabe entfernen */
    fun revoke() {
        val s = prefs.pirolFolderUri
        if (s != null) {
            try {
                context.contentResolver.releasePersistableUriPermission(Uri.parse(s), RW)
            } catch (_: Exception) { /* bereits entzogen */ }
        }
        prefs.pirolFolderUri = null
    }

    /** Textdatei direkt im Ordner lesen (null wenn nicht vorhanden / keine Freigabe) */
    fun readText(fileName: String): String? {
        val f = root()?.findFile(fileName) ?: return null
        if (!f.isFile) return null
        return try {
            context.contentResolver.openInputStream(f.uri)?.bufferedReader()?.use { it.readText() }
        } catch (e: Exception) {
            Log.w(TAG, "$fileName nicht lesbar: ${e.message}")
            null
        }
    }

    /** Alle *.json-Dateien aus lists/ als (Name, Inhalt); Lesefehler -> Inhalt null */
    fun readListFiles(): List<Pair<String, String?>> {
        val dir = root()?.findFile(CustomSpeciesLists.FOLDER_NAME) ?: return emptyList()
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles()
            .filter { it.isFile && (it.name ?: "").endsWith(".json", ignoreCase = true) }
            .sortedBy { it.name }
            .map { f ->
                val text = try {
                    context.contentResolver.openInputStream(f.uri)?.bufferedReader()?.use { it.readText() }
                } catch (e: Exception) { null }
                (f.name ?: "?") to text
            }
    }

    /** Legt lists/beispiel_liste.json an (falls nicht vorhanden). true bei Erfolg. */
    fun writeExampleList(): Boolean {
        val r = root() ?: return false
        return try {
            val dir = r.findFile(CustomSpeciesLists.FOLDER_NAME)?.takeIf { it.isDirectory }
                ?: r.createDirectory(CustomSpeciesLists.FOLDER_NAME) ?: return false
            if (dir.findFile(CustomSpeciesLists.EXAMPLE_FILE_NAME) != null) return true
            val f = dir.createFile("application/json", CustomSpeciesLists.EXAMPLE_FILE_NAME) ?: return false
            context.contentResolver.openOutputStream(f.uri)?.use {
                it.write(CustomSpeciesLists.EXAMPLE_CONTENT.toByteArray(Charsets.UTF_8))
            } ?: return false
            true
        } catch (e: Exception) {
            Log.w(TAG, "Beispielliste fehlgeschlagen: ${e.message}")
            false
        }
    }
}
