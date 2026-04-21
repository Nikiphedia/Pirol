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
    val verifiedCount: Int,
    val recordingSizeBytes: Long = 0L  // T52: WAV-Dateigrösse (0 = nicht ermittelbar)
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
    val recordingFile: File? = null,
    val recordingDurationSec: Float = 0f,

    // Sonogramm
    val spectrogramState: SpectrogramState = SpectrogramState(),
    // T56: Einmal-Perzentil (p5, p95) ueber gesamte Session-WAV. null wenn Toggle AUS
    // oder noch nicht berechnet — dann greifen manualMinDb/manualMaxDb aus Settings.
    val spectrogramRange: Pair<Float, Float>? = null,

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
    val detectionSpectrogramRange: Pair<Float, Float>? = null,
    val referenceEntries: List<ReferenceEntry> = emptyList(),
    val selectedReference: ReferenceEntry? = null,
    val referenceSpectrogramState: SpectrogramState = SpectrogramState(),
    val referenceSpectrogramRange: Pair<Float, Float>? = null,
    val playingSource: PlayingSource? = null,

    // T56: Sonogramm-Dynamik aus Settings (wirkt auch im Analyse-Tab).
    // autoContrast ON → Einmal-Perzentil (spectrogramRange) wird verwendet.
    // autoContrast OFF → manualMinDb/manualMaxDb.
    val spectrogramAutoContrast: Boolean = true,
    val spectrogramMinDb: Float = -80f,
    val spectrogramMaxDb: Float = 0f,
    // T56b: Gamma-Kompression (< 1.0 = leise Anteile heller, 1.0 = aus)
    val spectrogramGamma: Float = 1f,
    // T56b: Lautstärke-Deckel in dBFS (0 = aus, negativ = clippt Lautes)
    val spectrogramCeilingDb: Float = 0f,

    // Raven-Export Feedback
    val ravenExportMessage: String? = null
)
