package ch.etasystems.pirol.data.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * T272: Einzelnes Memo (Session- oder Art-Memo) teilen bzw. speichern. Die Memo-WAV wird unveraendert
 * kopiert (keine Mensch-Maskierung, keine Bearbeitung).
 *
 * @param source `File` (File-Modus) oder `Uri` (SAF), wie von `SessionManager.memoSource`
 */
class MemoExporter(private val context: Context) {

    /** Kopie in `cacheDir/share/<name>.wav` fuer das Share-Sheet (aeltere Dateien dort werden entfernt). */
    fun copyToShareCache(source: Any, name: String): File {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, "$name.wav")
        open(source).use { input -> target.outputStream().use { input.copyTo(it) } }
        return target
    }

    /** Teilen ueber das Share-Sheet (FileProvider). */
    fun share(file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "audio/wav"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, null).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
    }

    /** Speichern unter: Kopie in die per System-Dialog gewaehlte Zieldatei. */
    fun saveTo(source: Any, target: Uri) {
        val out = context.contentResolver.openOutputStream(target, "wt") ?: error("Zieldatei nicht schreibbar")
        out.use { o -> open(source).use { it.copyTo(o) } }
    }

    private fun open(source: Any): java.io.InputStream = when (source) {
        is File -> source.inputStream()
        is Uri -> context.contentResolver.openInputStream(source) ?: error("Memo nicht lesbar")
        else -> error("unbekannte Memo-Quelle")
    }
}
