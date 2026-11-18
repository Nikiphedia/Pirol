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

/**
 * Prozessweiter Upload-Status (T125): der Worker schreibt hier hinein, UploadManager reicht ihn als StateFlow weiter.
 */
object UploadStatusHolder {
    private val _status = kotlinx.coroutines.flow.MutableStateFlow<UploadStatus>(UploadStatus.Idle)
    val status: kotlinx.coroutines.flow.StateFlow<UploadStatus> = _status

    fun set(value: UploadStatus) { _status.value = value }
}
