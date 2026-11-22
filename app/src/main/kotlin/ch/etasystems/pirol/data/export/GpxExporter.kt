package ch.etasystems.pirol.data.export

import ch.etasystems.pirol.data.repository.SessionFiles
import ch.etasystems.pirol.location.TrackLogic
import ch.etasystems.pirol.location.TrackPoint
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * T191: Export der Route als GPX 1.1 (`track.gpx`, ein trk mit einem trkseg).
 *
 * Zeiten in UTC (`Z`): Das GPX-1.1-Schema schreibt UTC vor; viele Programme (Garmin, QGIS,
 * Strava, aeltere Parser) werten Offsets falsch oder gar nicht aus. Die Lokalzeit mit Offset
 * bleibt in `track.jsonl` erhalten (Zeitzone-Regel CLAUDE.md gilt fuer JSON/JSONL).
 */
object GpxExporter {
    const val GPX_FILE = "track.gpx"
    private const val CREATOR = "PIROL"

    /** XML-Sonderzeichen ersetzen; im XML 1.0 unzulaessige Steuerzeichen entfernen. */
    fun escapeXml(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            when (c) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&apos;")
                '\t', '\n', '\r' -> sb.append(c)
                else -> if (c >= ' ' && c != '￾' && c != '￿') sb.append(c)
            }
        }
        return sb.toString()
    }

    /** ISO-8601-Zeit des Punkts in UTC (`2026-04-20T12:30:00Z`); null = Zeit unlesbar */
    fun utcTime(point: TrackPoint): String? =
        TrackLogic.epochMs(point)?.let { DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(it)) }

    /**
     * GPX-Dokument aus den Punkten. [name] = Name der Spur (Session-ID),
     * [description] = optionaler Hinweis (z.B. "nur Detektions-Fixes").
     */
    fun build(points: List<TrackPoint>, name: String, description: String? = null): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"").append(CREATOR).append("\" ")
            .append("xmlns=\"http://www.topografix.com/GPX/1/1\" ")
            .append("xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" ")
            .append("xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd\">\n")
        sb.append("  <metadata>\n")
        sb.append("    <name>").append(escapeXml(name)).append("</name>\n")
        points.firstNotNullOfOrNull { utcTime(it) }?.let { sb.append("    <time>").append(it).append("</time>\n") }
        sb.append("  </metadata>\n")
        sb.append("  <trk>\n")
        sb.append("    <name>").append(escapeXml(name)).append("</name>\n")
        if (!description.isNullOrBlank()) sb.append("    <desc>").append(escapeXml(description)).append("</desc>\n")
        sb.append("    <trkseg>\n")
        for (p in points) {
            sb.append("      <trkpt lat=\"").append(fmt(p.lat, 7)).append("\" lon=\"").append(fmt(p.lon, 7)).append("\">")
            // Reihenfolge laut Schema: ele vor time
            p.altitudeM?.takeIf { !it.isNaN() && !it.isInfinite() }?.let { sb.append("<ele>").append(fmt(it, 1)).append("</ele>") }
            utcTime(p)?.let { sb.append("<time>").append(it).append("</time>") }
            sb.append("</trkpt>\n")
        }
        sb.append("    </trkseg>\n")
        sb.append("  </trk>\n")
        sb.append("</gpx>\n")
        return sb.toString()
    }

    /** Hinweistext, wenn die Spur nur aus Detektions-Fixes besteht (sprachneutral, Exportformat) */
    fun descriptionFor(points: List<TrackPoint>): String? =
        if (points.isNotEmpty() && points.all { it.detectionFix }) "GPS mode on-detection only: sparse track from detection fixes" else null

    /**
     * Liest `track.jsonl` der Session und schreibt `track.gpx` daneben.
     * @return Anzahl exportierter Punkte (0 = keine Spur, keine Datei geschrieben)
     */
    fun exportFrom(access: SessionFiles, name: String): Int {
        val text = access.readText(TrackLogic.TRACK_FILE) ?: return 0
        val points = TrackLogic.parseLines(text)
        if (points.isEmpty()) return 0
        access.writeReplacing(GPX_FILE, build(points, name, descriptionFor(points)))
        return points.size
    }

    private fun fmt(v: Double, decimals: Int): String = String.format(Locale.ROOT, "%.${decimals}f", v)
}
