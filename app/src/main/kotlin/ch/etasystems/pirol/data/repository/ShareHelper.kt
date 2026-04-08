package ch.etasystems.pirol.data.repository

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * Erstellt Share Intents fuer KML-Dateien via FileProvider.
 */
object ShareHelper {

    /**
     * Erstellt einen Share Intent fuer eine KML-Datei.
     *
     * @param context Android Context
     * @param file KML-Datei zum Teilen
     * @return Share Intent (ACTION_SEND)
     */
    fun shareKml(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.google-earth.kml+xml"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "PIROL Beobachtungen — ${file.nameWithoutExtension}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
