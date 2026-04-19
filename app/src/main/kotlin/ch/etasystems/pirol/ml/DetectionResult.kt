package ch.etasystems.pirol.ml

import kotlinx.serialization.Serializable

/**
 * Status der Benutzer-Verifikation einer Detektion.
 */
@Serializable
enum class VerificationStatus {
    UNVERIFIED,    // Standard: Automatische Detektion, nicht geprueft
    CONFIRMED,     // Nutzer hat bestaetigt: Art stimmt
    REJECTED,      // Nutzer hat abgelehnt: Falsch-Positiv
    CORRECTED,     // Nutzer hat korrigiert: Andere Art zugewiesen
    UNCERTAIN,     // Nutzer hat als unsicher markiert (T44)
    REPLACED       // Nutzer hat Alternative gewaehlt: Diese Detektion wurde ersetzt (T45)
}

/**
 * Alternativer Kandidat aus der BirdNET Top-K Klassifizierung.
 * Wird auf dem Top-Result als Liste mitgeliefert.
 */
@Serializable
data class DetectionCandidate(
    val scientificName: String,  // z.B. "Sylvia atricapilla"
    val commonName: String,      // z.B. "Moenchsgrasmuecke" (uebersetzt via SpeciesNameResolver)
    val confidence: Float         // 0.0 - 1.0
)

/**
 * Ergebnis einer Artenerkennung aus der Inference-Pipeline.
 *
 * Wird vom InferenceWorker erzeugt und im DetectionListState gesammelt.
 * Jede Detektion repraesentiert eine erkannte Art innerhalb eines 3-Sekunden-Fensters.
 */
@Serializable
data class DetectionResult(
    val id: String,              // UUID fuer eindeutige Identifikation
    val scientificName: String,  // z.B. "Turdus merula"
    val commonName: String,      // z.B. "Amsel"
    val confidence: Float,       // 0.0 - 1.0
    val timestampMs: Long,       // System.currentTimeMillis() zum Detektions-Zeitpunkt
    val chunkStartSec: Float,    // Sekunden seit Session-Start (Position in recording.wav)
    val chunkEndSec: Float,      // Sekunden seit Session-Start (Ende des Inference-Fensters)
    val sampleRate: Int,         // Rate der Aufnahme (48k/96k)
    val latitude: Double? = null,   // GPS Breitengrad (WGS84)
    val longitude: Double? = null,  // GPS Laengengrad (WGS84)
    val verificationStatus: VerificationStatus = VerificationStatus.UNVERIFIED,
    val correctedSpecies: String? = null,   // Nur bei CORRECTED: korrigierter Artname
    val verifiedAtMs: Long? = null,         // Zeitpunkt der Verifikation
    val candidates: List<DetectionCandidate> = emptyList(),  // Top-N Alternativen aus BirdNET
    val detectionCount: Int = 1,             // Wie oft wurde diese Art erkannt (Dedup-Zaehler)
    val lastDetectedMs: Long = 0L            // Zeitpunkt der letzten Erkennung (fuer 5s-Highlight, T33)
)

/**
 * Verifikations-Event fuer verifications.jsonl.
 * Separate Persistierung, Rohdaten (detections.jsonl) bleiben unveraendert.
 */
@Serializable
data class VerificationEvent(
    val detectionId: String,
    val status: VerificationStatus,
    val correctedSpecies: String? = null,
    val verifiedAtMs: Long
)
