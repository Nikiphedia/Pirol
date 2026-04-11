package ch.etasystems.pirol.data.repository

import kotlinx.serialization.Serializable

/**
 * Eine lokale Referenz-Aufnahme (aus Detektion oder Xeno-Canto Download).
 * Wird als Audio-Datei in filesDir/references/{scientificName}/ gespeichert.
 */
@Serializable
data class ReferenceEntry(
    val id: String,                     // UUID (8 Zeichen)
    val scientificName: String,         // "Turdus_merula" (Unterstrich-Format)
    val commonName: String,             // "Amsel"
    val confidence: Float,              // Urspruengliche BirdNET-Konfidenz (0.0 fuer XC)
    val audioFileName: String = "",      // "ref_001_Turdus_merula.wav" oder ".mp3"
    val sourceSessionId: String = "",   // Session aus der die Aufnahme stammt (leer fuer XC)
    val sourceDetectionId: String = "", // Original DetectionResult.id (leer fuer XC)
    val recordedAtMs: Long,             // Zeitpunkt der Aufnahme
    val addedAtMs: Long,                // Zeitpunkt des Hinzufuegens zur Bibliothek
    val latitude: Double? = null,
    val longitude: Double? = null,
    val verificationStatus: String = "CONFIRMED", // "CONFIRMED" oder "CORRECTED"
    val source: String = "local",       // "local" oder "xeno-canto"
    val xenoCantoId: String? = null,    // z.B. "XC123456"
    val recordist: String? = null       // Name des Aufnehmenden
)

/**
 * Index-Datei fuer die Referenzbibliothek (index.json).
 * Enthaelt alle ReferenceEntry-Eintraege.
 */
@Serializable
data class ReferenceIndex(
    val version: Int = 1,
    val updatedAt: String,              // ISO 8601
    val totalSpecies: Int,
    val totalRecordings: Int,
    val entries: List<ReferenceEntry>
)
