package ch.etasystems.pirol.data.sync

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import ch.etasystems.pirol.data.repository.SessionMetadata
import ch.etasystems.pirol.data.export.ExportNames
import java.io.File

/**
 * Kopiert die Session-ZIP in einen vom Benutzer gewaehlten Ordner (SAF, ACTION_OPEN_DOCUMENT_TREE).
 * Funktioniert mit jedem Document Provider (lokal, Google Drive, OneDrive, ...), sofern die App
 * den Baum-URI dauerhaft (persistable) freigegeben bekam.
 */
class SafFolderTarget(
    private val context: Context,
    private val treeUri: Uri
) : UploadTarget {

    override val name = "Ordner"

    /** Prueft, ob der Ordner noch erreichbar und beschreibbar ist; sonst Fehlermeldung (Deutsch). */
    fun check(): String? {
        val dir = DocumentFile.fromTreeUri(context, treeUri)
        return when {
            dir == null || !dir.exists() -> "Ordner nicht mehr verfuegbar - bitte neu waehlen"
            !dir.canWrite() -> "Keine Schreibrechte auf den Ordner - bitte neu waehlen"
            else -> null
        }
    }

    override suspend fun upload(
        zipFile: File,
        metadata: SessionMetadata,
        onProgress: (Float) -> Unit
    ): Boolean {
        check()?.let { throw UploadConfigException(it) }
        val dir = DocumentFile.fromTreeUri(context, treeUri)!!
        val fileName = UploadConfig.sanitizeFileName(
            ExportNames.sessionZipName(metadata.startedAt, metadata.note, metadata.sessionId)
        )
        // Bestehende Datei gleichen Namens ersetzen (Wiederholung nach Abbruch)
        dir.findFile(fileName)?.delete()
        val target = dir.createFile("application/zip", fileName)
            ?: throw UploadConfigException("Datei im Zielordner nicht erstellbar")
        onProgress(0.1f)
        val total = zipFile.length().coerceAtLeast(1L)
        var written = 0L
        val out = context.contentResolver.openOutputStream(target.uri, "w")
            ?: throw UploadConfigException("Zieldatei nicht beschreibbar")
        out.use { os ->
            zipFile.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    os.write(buf, 0, n)
                    written += n
                    onProgress(0.1f + 0.9f * (written.toFloat() / total))
                }
            }
        }
        onProgress(1f)
        return true
    }
}
