package ch.etasystems.pirol.ui.live

import ch.etasystems.pirol.audio.dsp.SpectrogramConfig
import ch.etasystems.pirol.data.sync.UploadStatus
import ch.etasystems.pirol.ml.DetectionListState
import ch.etasystems.pirol.ml.EmbeddingDatabase
import ch.etasystems.pirol.ml.InferenceConfig
import ch.etasystems.pirol.ui.components.SpectrogramPalette
import ch.etasystems.pirol.ui.components.SpectrogramState

/**
 * FAB-Zustaende fuer den Aufnahme-Button.
 */
enum class RecordingFabState {
    /** Kein Service gebunden — Mic-Icon, primary, enabled */
    IDLE,
    /** Service bindet gerade (< 500ms) — Mic-Icon, ausgegraut, disabled */
    CONNECTING,
    /** Service gebunden, nicht recording — Mic-Icon, primary, enabled */
    READY,
    /** Aufnahme laeuft — Stop-Icon, rot */
    RECORDING
}

/**
 * Immutable UI-State fuer den LiveScreen.
 * Wird vom LiveViewModel via StateFlow bereitgestellt.
 */
data class LiveUiState(
    val isRecording: Boolean = false,
    val isServiceBound: Boolean = false,
    val actualSampleRate: Int = 0,
    val recordingStartElapsedRealtime: Long = 0L,
    val spectrogramState: SpectrogramState = SpectrogramState(),
    val spectrogramConfig: SpectrogramConfig = SpectrogramConfig.BIRDS,
    val palette: SpectrogramPalette = SpectrogramPalette.GRAYSCALE,
    val permissionGranted: Boolean = false,
    // ML-Pipeline State (T9/T10)
    val detectionListState: DetectionListState = DetectionListState(),
    val isModelAvailable: Boolean = false,
    // Inference-Konfiguration (T11)
    val inferenceConfig: InferenceConfig = InferenceConfig.DEFAULT,
    // Embedding/Similarity State (T12)
    val similarMatches: List<EmbeddingDatabase.EmbeddingMatch> = emptyList(),
    val isEmbeddingAvailable: Boolean = false,
    val embeddingDbSize: Int = 0,
    // GPS-State (T13)
    val currentLatitude: Double? = null,
    val currentLongitude: Double? = null,
    val locationAccuracyM: Float? = null,
    val isLocationAvailable: Boolean = false,
    // Letzte Session-ID (fuer Upload-Button, T17)
    val lastSessionId: String? = null,
    // Speicherort-Fallback (T51): true wenn SAF-URI nicht erreichbar war
    val storageUnavailableFallback: Boolean = false,
    // Upload-Status (T17)
    val uploadStatus: UploadStatus = UploadStatus.Idle,
    // Watchlist-State (T20)
    val watchlistSpecies: Set<String> = emptySet()
) {
    /** Abgeleiteter FAB-State basierend auf Service- und Recording-Zustand */
    val fabState: RecordingFabState
        get() = when {
            isRecording -> RecordingFabState.RECORDING
            isServiceBound -> RecordingFabState.READY
            else -> RecordingFabState.IDLE
        }
}
