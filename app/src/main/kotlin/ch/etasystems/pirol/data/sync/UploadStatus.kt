package ch.etasystems.pirol.data.sync

/**
 * Status eines Upload-Vorgangs.
 */
sealed class UploadStatus {
    data object Idle : UploadStatus()
    data class InProgress(val sessionId: String, val progress: Float) : UploadStatus()
    data class Success(val sessionId: String) : UploadStatus()
    data class Failed(val sessionId: String, val error: String) : UploadStatus()
    /**
     * T263: Das aktive Modell kennt keine Menschen, ein Export mit Ausblenden braucht einen Entscheid.
     * [manual] = der Nutzer hat den Export selbst ausgeloest (Dialog zeigen); sonst Auto-Upload (nicht hochgeladen,
     * der Dialog kommt beim naechsten manuellen Export).
     */
    data class AwaitingDecision(val sessionId: String, val manual: Boolean) : UploadStatus()
}

/**
 * Prozessweiter Upload-Status (T125): der Worker schreibt hier hinein, UploadManager reicht ihn als StateFlow weiter.
 */
object UploadStatusHolder {
    private val _status = kotlinx.coroutines.flow.MutableStateFlow<UploadStatus>(UploadStatus.Idle)
    val status: kotlinx.coroutines.flow.StateFlow<UploadStatus> = _status

    fun set(value: UploadStatus) { _status.value = value }
}
