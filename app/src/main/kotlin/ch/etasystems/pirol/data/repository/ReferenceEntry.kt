package ch.etasystems.pirol.data.repository

import kotlinx.serialization.Serializable

/**
 * Eine lokale Referenz-Aufnahme aus einer verifizierten Detektion.
 * Wird als WAV-Datei in filesDir/references/{scientificName}/ gespeichert.
 */
@Serializable
data class ReferenceEntry(
    val id: String,                     // UUID (8 Zeichen)
    val scientificName: String,         // "Turdus_merula" (Unterstrich-Format)
    val commonName: String,             // "Amsel"
    val confidence: Float,              // Urspruengliche BirdNET-Konfidenz
    val wavFileName: String,            // "ref_001_Turdus_merula.wav"
    val sourceSessionId: String,        // Session aus der die Aufnahme stammt
    val sourceDetectionId: String,      // Original DetectionResult.id
    val recordedAtMs: Long,             // Zeitpunkt der Aufnahme
    val addedAtMs: Long,                // Zeitpunkt des Hinzufuegens zur Bibliothek
    val latitude: Double? = null,
    val longitude: Double? = null,
    val verificationStatus: String      // "CONFIRMED" oder "CORRECTED"
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
