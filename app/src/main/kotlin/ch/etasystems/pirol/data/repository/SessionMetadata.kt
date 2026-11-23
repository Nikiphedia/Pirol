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
    val durationSec: Float,     // Laenge dieses Segments in Sekunden
    /**
     * T255: Wanduhr des Segmentbeginns (ISO 8601 mit Offset). Nur bei importierten Sessions aus mehreren
     * Dateien gesetzt (Luecken zwischen den Dateien); null = Segment schliesst luecken-los an (live).
     */
    val startedAt: String? = null
)

/** T255: Herkunft einer importierten Audiodatei (session.json, additiv). */
@Serializable
data class ImportedSource(
    val fileName: String,
    /** Dateiformat (Endung bzw. MIME), z.B. "wav", "flac", "m4a" */
    val format: String,
    /** Abtastrate der Originaldatei */
    val sampleRate: Int,
    val channels: Int,
    val durationSec: Float? = null,
    /** Aufnahmebeginn dieser Datei (ISO 8601 mit Offset) */
    val startedAt: String,
    /** Herkunft der Zeit: guano, audiomoth, bext, metadata, info, filename, modified, manual */
    val timeSource: String
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
    val recordingSegments: List<RecordingSegment>? = null,  // T57-B1: null bei Single-File (Rueckwaertskompatibilitaet)
    val note: String? = null,       // T109: Freitext-Bemerkung zur Session (null = keine; alte session.json ohne Feld)
    val modelId: String? = null,    // T103a: Profil-ID des Modells (z.B. "v3_final_fp16")
    val speciesFilter: SpeciesFilterInfo? = null,  // T218/T246: Gruppenwahl und Geomodel-Angaben
    val importedFrom: List<ImportedSource>? = null  // T255: importierte Fremdaudio-Dateien (null = Aufnahme mit PIROL)
)

/**
 * T218/T246: Artenfilter einer Session (nur Gruppenfilter). [groups] = Quelle je Artgruppe.
 * [source]: "list" (keine Gruppe auf Auto), "gps" (frischer Fix), "saved" (gespeicherte Position),
 * "session" (Rescan mit Session-Position), "fallback" (Ersatzlisten, kein Geomodel).
 * Geomodel-Angaben nur bei einer Gruppe auf Auto; Koordinaten auf 2 Dezimalen gerundet (ca. 1 km).
 */
@Serializable
data class SpeciesFilterInfo(
    val source: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val week: Int? = null,
    val threshold: Float? = null,
    val yearly: Boolean = false,
    val model: String? = null,
    /** T246: Ersatzliste je Gruppe auf Auto (Gruppen-Code -> Liste): ohne Position/Geomodel und fuer dem Geomodel unbekannte Arten */
    val fallbacks: Map<String, String>? = null,
    /** T232: Quelle je Artgruppe (birds/orthoptera/amphibians/mammals/other -> off/all/auto_geo/Region-ID) */
    val groups: Map<String, String>? = null
)
