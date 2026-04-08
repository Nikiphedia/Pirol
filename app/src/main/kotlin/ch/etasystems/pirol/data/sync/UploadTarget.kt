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
     * @param onProgress Fortschritt 0.0-1.0
     * @return true bei Erfolg
     */
    suspend fun upload(
        zipFile: File,
        metadata: SessionMetadata,
        onProgress: (Float) -> Unit = {}
    ): Boolean
}
