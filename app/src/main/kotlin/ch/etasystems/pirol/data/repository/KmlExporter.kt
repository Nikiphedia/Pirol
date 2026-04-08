package ch.etasystems.pirol.data.repository

import android.util.Log
import ch.etasystems.pirol.ml.DetectionResult
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Exportiert eine Session als KML-Datei (Keyhole Markup Language).
 * Jede Detektion mit GPS-Koordinaten wird ein Placemark.
 */
object KmlExporter {

    private const val TAG = "KmlExporter"

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Liest Session-Daten und generiert eine KML-Datei.
     *
     * @param sessionDir Session-Ordner (mit session.json + detections.jsonl)
     * @param exportDir Zielordner fuer die KML-Datei (wird erstellt falls noetig)
     * @return KML-Datei oder null bei Fehler / keine Detektionen mit GPS
     */
    fun export(sessionDir: File, exportDir: File): File? {
        try {
            // session.json lesen
            val metadataFile = File(sessionDir, "session.json")
            if (!metadataFile.exists()) {
                Log.w(TAG, "session.json nicht gefunden: ${sessionDir.name}")
                return null
            }
            val metadata = json.decodeFromString<SessionMetadata>(metadataFile.readText())

            // detections.jsonl lesen (Zeile fuer Zeile)
            val detectionsFile = File(sessionDir, "detections.jsonl")
            if (!detectionsFile.exists()) {
                Log.w(TAG, "detections.jsonl nicht gefunden: ${sessionDir.name}")
                return null
            }

            val detections = detectionsFile.readLines()
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    try {
                        json.decodeFromString<DetectionResult>(line)
                    } catch (e: Exception) {
                        Log.w(TAG, "Detektion nicht parsebar: $line", e)
                        null
                    }
                }

            // Nur Detektionen mit GPS behalten
            val geoDetections = detections.filter { it.latitude != null && it.longitude != null }
            if (geoDetections.isEmpty()) {
                Log.i(TAG, "Keine Detektionen mit GPS in Session ${metadata.sessionId}")
                return null
            }

            // KML generieren
            val kml = generateKml(metadata, geoDetections)

            // Export-Ordner erstellen + Datei schreiben
            exportDir.mkdirs()
            val kmlFile = File(exportDir, "${metadata.sessionId}.kml")
            kmlFile.writeText(kml)

            Log.i(TAG, "KML exportiert: ${kmlFile.name} (${geoDetections.size} Placemarks)")
            return kmlFile

        } catch (e: Exception) {
            Log.e(TAG, "KML-Export fehlgeschlagen", e)
            return null
        }
    }

    /**
     * Generiert einen KML-String aus Session-Metadaten und Detektionen.
     */
    internal fun generateKml(metadata: SessionMetadata, detections: List<DetectionResult>): String {
        val sb = StringBuilder()
        sb.appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        sb.appendLine("""<kml xmlns="http://www.opengis.net/kml/2.2">""")
        sb.appendLine("""  <Document>""")
        sb.appendLine("""    <name>PIROL Session ${escapeXml(metadata.sessionId)}</name>""")
        sb.appendLine("""    <description>${detections.size} Detektionen, ${metadata.sampleRate} Hz, Region: ${metadata.regionFilter ?: "alle"}</description>""")

        // Style fuer Vogel-Marker
        sb.appendLine("""    <Style id="bird-detection">""")
        sb.appendLine("""      <IconStyle><color>ff00aa00</color><scale>1.0</scale></IconStyle>""")
        sb.appendLine("""      <LabelStyle><scale>0.8</scale></LabelStyle>""")
        sb.appendLine("""    </Style>""")

        // Placemarks
        for (det in detections) {
            if (det.latitude == null || det.longitude == null) continue
            val pct = (det.confidence * 100).toInt()
            val timestamp = java.time.Instant.ofEpochMilli(det.timestampMs).toString()
            sb.appendLine("""    <Placemark>""")
            sb.appendLine("""      <name>${escapeXml(det.commonName)} ($pct%)</name>""")
            sb.appendLine("""      <description>${escapeXml(det.scientificName)} — $timestamp</description>""")
            sb.appendLine("""      <styleUrl>#bird-detection</styleUrl>""")
            sb.appendLine("""      <TimeStamp><when>$timestamp</when></TimeStamp>""")
            // KML-Koordinaten: longitude,latitude,altitude
            sb.appendLine("""      <Point><coordinates>${det.longitude},${det.latitude},0</coordinates></Point>""")
            sb.appendLine("""    </Placemark>""")
        }

        sb.appendLine("""  </Document>""")
        sb.appendLine("""</kml>""")
        return sb.toString()
    }

    /**
     * Einfaches XML-Escaping fuer Textinhalte.
     */
    private fun escapeXml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
