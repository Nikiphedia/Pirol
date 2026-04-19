package ch.etasystems.pirol.data.repository

import kotlinx.serialization.Serializable

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
    val totalDetections: Int = 0
)
