package ch.etasystems.pirol.data.sync

import android.content.Context
import android.os.Environment
import ch.etasystems.pirol.data.repository.SessionMetadata
import java.io.File

/**
 * Exportiert Sessions als ZIP in den Downloads-Ordner.
 * Nutzt Environment.DIRECTORY_DOWNLOADS (kein WRITE_EXTERNAL_STORAGE noetig ab API 29).
 *
 * Ergebnis: Downloads/PIROL/YYYYMMDDHHMM[_Bemerkung].zip
 */
class LocalExportTarget(private val context: Context) : UploadTarget {

    override val name = "Lokaler Export"

    override fun exists(fileName: String): Boolean = File(pirolDir(), fileName).exists()

    private fun pirolDir(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "PIROL")

    override suspend fun upload(
        zipFile: File,
        metadata: SessionMetadata,
        fileName: String,
        onProgress: (Float) -> Unit
    ): Boolean {
        // Zielordner: Downloads/PIROL/
        val downloadsDir = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOWNLOADS
        )
        val pirolDir = File(downloadsDir, "PIROL")
        pirolDir.mkdirs()

        val targetFile = File(pirolDir, fileName)

        return try {
            onProgress(0.1f)
            zipFile.copyTo(targetFile, overwrite = true)
            onProgress(1.0f)
            true
        } catch (e: Exception) {
            false
        }
    }
}
