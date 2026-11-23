package ch.etasystems.pirol.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/** T190: Oeffnet den Browser-Listen-Editor (lokale Asset-Kopie per FileProvider, spaeter Online-URL). */
object ListEditorLauncher {

    /** Pfad der Editor-Datei in den Assets (liefert T189) */
    const val EDITOR_ASSET_PATH = "editor/listen-editor.html"

    /** TODO: Online-URL (GitHub Pages) setzen, sobald das Repo angelegt ist; dann hat sie Vorrang. */
    val EDITOR_ONLINE_URL: String? = null

    /** Ziel im cacheDir/share/ (FileProvider-Pfad "share") */
    private const val CACHE_NAME = "listen-editor.html"

    /** @return true wenn ein Browser geoeffnet wurde, false wenn Asset fehlt oder kein Browser vorhanden */
    fun open(context: Context): Boolean {
        val online = EDITOR_ONLINE_URL
        val intent = if (online != null) {
            Intent(Intent.ACTION_VIEW, Uri.parse(online))
        } else {
            val file = try {
                val dir = File(context.cacheDir, "share").apply { mkdirs() }
                File(dir, CACHE_NAME).also { out ->
                    context.assets.open(EDITOR_ASSET_PATH).use { input -> out.outputStream().use { input.copyTo(it) } }
                }
            } catch (e: Exception) {
                return false
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "text/html")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: Exception) {
            false
        }
    }
}
