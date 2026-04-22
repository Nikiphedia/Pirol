package ch.etasystems.pirol.data.repository

import kotlinx.serialization.Serializable

/**
 * GPS-Statistiken einer Session (T53).
 * Befuellt vom LocationProvider / LiveViewModel, geschrieben beim SessionManager.stop().
 */
@Serializable
data class GpsStats(
    val fixCount: Int,          // Akzeptierte Fixes waehrend Session
    val rejectedCount: Int,     // Verworfene Fixes (accuracy > gpsMaxAccuracyMeters)
    val medianAccuracy: Float,  // Median der akzeptierten Fix-Genauigkeiten in Metern
    val intervalMs: Long        // Konfiguriertes GPS-Intervall in Millisekunden
)

/**
 * Ein WAV-Segment einer Session (T57-B1: Session-Rotation).
 * Entsteht wenn die maximale Aufnahmelaenge erreicht wird.
 */
@Serializable
data class RecordingSegment(
    val fileName: String,       // "recording.wav", "recording-002.wav", ...
    val startSec: Float,        // Offset zum Session-Start in Sekunden
    val durationSec: Float      // Laenge dieses Segments in Sekunden
)

/**
 * Metadaten einer Aufnahme-Session.
 * Wird als session.json im Session-Ordner gespeichert.
 */
@Serializable
data class SessionMetadata(
    val sessionId: String,
    val startedAt: String,          // ISO 8601
    val endedAt: String? = null,    // null waehrend Session laeuft
    val sampleRate: Int,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val regionFilter: String? = null,
    val confidenceThreshold: Float,
    val totalRecordedSamples: Long = 0L,
    val totalDetections: Int = 0,
    val gpsStats: GpsStats? = null,         // T53: nullable fuer Rueckwaertskompatibilitaet mit alten Sessions
    val recordingSegments: List<RecordingSegment>? = null  // T57-B1: null bei Single-File (Rueckwaertskompatibilitaet)
)
