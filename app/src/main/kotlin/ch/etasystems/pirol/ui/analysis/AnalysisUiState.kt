package ch.etasystems.pirol.ui.analysis

import ch.etasystems.pirol.audio.AudioPlayer
import ch.etasystems.pirol.data.repository.ReferenceEntry
import ch.etasystems.pirol.data.repository.SessionMetadata
import ch.etasystems.pirol.ml.DetectionResult
import ch.etasystems.pirol.ui.components.SpectrogramState
import java.io.File

/**
 * Zusammenfassung einer Session fuer die Listen-Ansicht.
 */
data class SessionSummary(
    val sessionDir: File,
    val metadata: SessionMetadata,
    val detectionCount: Int,
    val verifiedCount: Int
)

/** Was gerade abgespielt wird im Vergleichs-Modus */
enum class PlayingSource { DETECTION, REFERENCE }

/**
 * UI-State fuer den Analyse-Tab (Session-Browser + Detail + Vergleich).
 */
data class AnalysisUiState(
    // Session-Liste
    val sessions: List<SessionSummary> = emptyList(),
    val isLoading: Boolean = false,

    // Ausgewaehlte Session (null = Liste anzeigen)
    val selectedSession: SessionSummary? = null,
    val detections: List<DetectionResult> = emptyList(),
    val audioChunks: List<File> = emptyList(),

    // Chunk-Navigation + Sonogramm
    val currentChunkIndex: Int = 0,
    val spectrogramState: SpectrogramState = SpectrogramState(),

    // Scroll-Signal (T39: nach Jump zum Chunk soll Sonogramm sichtbar sein)
    val scrollToTop: Boolean = false,

    // Audio-Playback
    val playbackState: AudioPlayer.PlaybackState = AudioPlayer.PlaybackState.IDLE,
    val playingChunkIndex: Int? = null,

    // Vergleichs-Modus (Ebene 3)
    val isCompareMode: Boolean = false,
    val compareDetection: DetectionResult? = null,
    val compareDetectionChunkIndex: Int? = null,
    val detectionSpectrogramState: SpectrogramState = SpectrogramState(),
    val referenceEntries: List<ReferenceEntry> = emptyList(),
    val selectedReference: ReferenceEntry? = null,
    val referenceSpectrogramState: SpectrogramState = SpectrogramState(),
    val playingSource: PlayingSource? = null
)
