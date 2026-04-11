package ch.etasystems.pirol.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Xeno-Canto API v3 Paginierte Antwort */
@Serializable
data class XenoCantoResponse(
    val numRecordings: String,
    val numSpecies: String,
    val page: Int,
    val numPages: Int,
    val recordings: List<XenoCantoRecording>
)

/** Einzelne Aufnahme aus Xeno-Canto */
@Serializable
data class XenoCantoRecording(
    val id: String,
    val gen: String,           // Genus
    val sp: String,            // Species
    val ssp: String = "",      // Subspecies
    val grp: String = "",      // Group (birds, grasshoppers, etc.)
    val en: String = "",       // English common name
    val cnt: String = "",      // Country
    val loc: String = "",      // Location
    val lat: String? = null,   // Latitude
    @SerialName("lon")
    val lon: String? = null,   // Longitude (v3: "lon", nicht "lng")
    val q: String = "",        // Quality A-E
    val length: String = "",   // Duration "0:45"
    val file: String = "",     // Audio download URL (oft protocol-relative)
    val sono: SonogramUrls = SonogramUrls(),
    val type: String = "",     // "call", "song", etc.
    val rec: String = ""       // Recordist
) {
    /** Wissenschaftlicher Name: "Turdus_merula" (Unterstrich-Format gemaess Konvention) */
    val scientificName: String get() = "$gen $sp".trim().replace(' ', '_')

    /** HTTPS-Audio-URL (protocol-relative URLs konvertieren) */
    val audioUrl: String get() = if (file.startsWith("//")) "https:$file" else file
}

/** Sonogramm-URLs (verschiedene Groessen) */
@Serializable
data class SonogramUrls(
    val small: String = "",
    val med: String = "",
    val large: String = "",
    val full: String = ""
)
