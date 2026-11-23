package ch.etasystems.pirol.data.sync

import ch.etasystems.pirol.data.repository.SessionMetadata
import java.io.File

/**
 * Abstraktion fuer Upload-Backends.
 * Implementierungen: LocalExportTarget (ZIP nach Downloads),
 * spaeter: DriveTarget, HttpTarget, SftpTarget.
 */
interface UploadTarget {

    /** Eindeutiger Name des Targets (fuer Settings/Logs) */
    val name: String

    /**
     * Laedt eine Session-ZIP-Datei hoch.
     *
     * @param zipFile Gepackte Session (ZIP mit session.json, detections.jsonl, audio/)
     * @param metadata Session-Metadaten (fuer Ordnernamen, Beschreibung etc.)
     * @param fileName Zieldateiname (T233: pro Session einmal kollisionsfrei festgelegt)
     * @param onProgress Fortschritt 0.0-1.0
     * @return true bei Erfolg
     */
    /** true, wenn im Ziel bereits eine Datei dieses Namens liegt (blockierend, im Worker aufrufen). */
    fun exists(fileName: String): Boolean

    suspend fun upload(
        zipFile: File,
        metadata: SessionMetadata,
        fileName: String,
        onProgress: (Float) -> Unit = {}
    ): Boolean
}
