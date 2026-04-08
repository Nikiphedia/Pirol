package ch.etasystems.pirol.data.sync

/**
 * Status eines Upload-Vorgangs.
 */
sealed class UploadStatus {
    data object Idle : UploadStatus()
    data class InProgress(val sessionId: String, val progress: Float) : UploadStatus()
    data class Success(val sessionId: String) : UploadStatus()
    data class Failed(val sessionId: String, val error: String) : UploadStatus()
}
