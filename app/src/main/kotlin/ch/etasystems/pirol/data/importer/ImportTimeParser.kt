package ch.etasystems.pirol.data.importer

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** T255: Herkunft der Aufnahmezeit einer importierten Datei (Reihenfolge = Vorrang). */
enum class TimeSource {
    /** GUANO-Metadaten (Fledermaus-/Bioakustik-Standard), "Timestamp" */
    GUANO,
    /** AudioMoth-Kommentar "Recorded at HH:MM:SS DD/MM/YYYY (UTC+x)" */
    AUDIOMOTH,
    /** Broadcast-WAV (Zoom u.a.): OriginationDate + OriginationTime */
    BEXT,
    /** Container-Metadaten (M4A/MP3, MediaMetadataRetriever) */
    METADATA,
    /** WAV LIST/INFO ICRD mit Uhrzeit */
    INFO,
    /** Dateiname (AudioMoth `YYYYMMDD_HHMMSS`, `YYYY-MM-DD_HH-MM-SS` u.a.) */
    FILENAME,
    /** Datei-Aenderungsdatum minus Dauer (unsicher: Kopieren aendert es) */
    MODIFIED,
    /** vom Nutzer eingegeben */
    MANUAL
}

/** Erkannte Aufnahmezeit (Start der Datei) mit Herkunft. */
data class TimeGuess(val time: ZonedDateTime, val source: TimeSource)

/**
 * T255: Aufnahmezeit und Ort aus Metadaten und Dateinamen (reines JVM, unit-testbar).
 * Alle Zeiten werden in die Zone [zone] (Geraet) umgerechnet, damit session.json lokale Zeit mit Offset traegt
 * (CLAUDE.md Zeitzonen-Regel). Angaben ohne Zone gelten als Ortszeit in [zone].
 */
object ImportTimeParser {

    private const val MIN_YEAR = 1990
    private const val MAX_YEAR = 2100

    /** Datum/Zeit im Dateinamen: YYYYMMDD_HHMMSS, YYYY-MM-DD_HH-MM-SS, YYYYMMDD-HHMMSS, YYYY-MM-DD HH.MM.SS, ...T... */
    private val NAME = Regex("""(?<!\d)(\d{4})(-?)(\d{2})\2(\d{2})[ _T-]?(\d{2})([-.:h]?)(\d{2})\6(\d{2})(?!\d)""")

    /** AudioMoth: "Recorded at 05:00:00 01/05/2024 (UTC+2) ..." (auch UTC, UTC-5, UTC+5:30) */
    private val AUDIOMOTH = Regex("""Recorded at (\d{2}):(\d{2}):(\d{2}) (\d{2})/(\d{2})/(\d{4}) \(UTC(?:([+-])(\d{1,2})(?::(\d{2}))?)?\)""")

    /** MediaMetadataRetriever METADATA_KEY_DATE: "20240501T050000.000Z" */
    private val MEDIA_DATE = Regex("""^(\d{8})T(\d{6})(?:\.\d+)?(Z)?$""")

    /** ISO 6709: "+47.3769+008.5417/" (Hoehe optional) */
    private val ISO6709 = Regex("""^([+-]\d+(?:\.\d+)?)([+-]\d+(?:\.\d+)?)""")

    private val INPUT_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    // ── Einzelquellen ────────────────────────────────────────────────

    /** Zeit aus dem Dateinamen (Ortszeit in [zone]); null = kein gueltiges Muster. */
    fun fromFileName(name: String, zone: ZoneId): ZonedDateTime? {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
        for (m in NAME.findAll(base)) {
            val g = m.groupValues
            val dt = localOrNull(g[1], g[3], g[4], g[5], g[7], g[8]) ?: continue
            return dt.atZone(zone)
        }
        return null
    }

    /** GUANO-Text ("Key: Value" je Zeile): Zeit ("Timestamp") und Position ("Loc Position: lat lon"). */
    data class GuanoInfo(val time: ZonedDateTime?, val latitude: Double?, val longitude: Double?)

    fun parseGuano(text: String, zone: ZoneId): GuanoInfo {
        var time: ZonedDateTime? = null
        var lat: Double? = null
        var lon: Double? = null
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            val colon = line.indexOf(':')
            if (colon <= 0) return@forEach
            val key = line.substring(0, colon).trim()
            val value = line.substring(colon + 1).trim()
            when (key) {
                "Timestamp" -> time = parseFlexible(value, zone)
                "Loc Position" -> {
                    val parts = value.split(Regex("[\\s,]+")).mapNotNull { it.toDoubleOrNull() }
                    if (parts.size >= 2 && validLatLon(parts[0], parts[1])) { lat = parts[0]; lon = parts[1] }
                }
            }
        }
        return GuanoInfo(time, lat, lon)
    }

    /** AudioMoth-Kommentar (WAV ICMT) mit Zeitzone; null = kein AudioMoth-Text. */
    fun fromAudioMothComment(text: String, zone: ZoneId): ZonedDateTime? {
        val m = AUDIOMOTH.find(text) ?: return null
        val g = m.groupValues
        val local = localOrNull(g[6], g[5], g[4], g[1], g[2], g[3]) ?: return null
        val hours = g[8].toIntOrNull() ?: 0
        val minutes = g[9].toIntOrNull() ?: 0
        val sign = if (g[7] == "-") -1 else 1
        val offset = runCatching { ZoneOffset.ofHoursMinutes(sign * hours, sign * minutes) }.getOrNull() ?: return null
        return local.atOffset(offset).atZoneSameInstant(zone)
    }

    /** Broadcast-WAV: OriginationDate (yyyy-mm-dd, beliebige Trenner) + OriginationTime (hh:mm:ss), Ortszeit. */
    fun fromBext(date: String?, time: String?, zone: ZoneId): ZonedDateTime? {
        val d = date?.filter { it.isDigit() } ?: return null
        val t = time?.filter { it.isDigit() } ?: return null
        if (d.length != 8 || t.length != 6) return null
        return localOrNull(d.substring(0, 4), d.substring(4, 6), d.substring(6, 8),
            t.substring(0, 2), t.substring(2, 4), t.substring(4, 6))?.atZone(zone)
    }

    /** WAV INFO ICRD: nur mit Uhrzeit verwertbar (reines Datum = null). */
    fun fromInfoDate(value: String?, zone: ZoneId): ZonedDateTime? {
        val v = value?.trim() ?: return null
        if (v.length < 16) return null
        return parseFlexible(v, zone)
    }

    /** Container-Datum (MediaMetadataRetriever): "20240501T050000.000Z" = UTC; ohne Z = Ortszeit. */
    fun fromMediaDate(value: String?, zone: ZoneId): ZonedDateTime? {
        val m = MEDIA_DATE.find(value?.trim() ?: return null) ?: return null
        val d = m.groupValues[1]
        val t = m.groupValues[2]
        val local = localOrNull(d.substring(0, 4), d.substring(4, 6), d.substring(6, 8),
            t.substring(0, 2), t.substring(2, 4), t.substring(4, 6)) ?: return null
        return if (m.groupValues[3] == "Z") local.atOffset(ZoneOffset.UTC).atZoneSameInstant(zone) else local.atZone(zone)
    }

    /** ISO-6709-Position ("+47.3769+008.5417/"); null = ungueltig. */
    fun parseIso6709(value: String?): Pair<Double, Double>? {
        val m = ISO6709.find(value?.trim() ?: return null) ?: return null
        val lat = m.groupValues[1].toDoubleOrNull() ?: return null
        val lon = m.groupValues[2].toDoubleOrNull() ?: return null
        return if (validLatLon(lat, lon) && !(lat == 0.0 && lon == 0.0)) lat to lon else null
    }

    /** Aenderungsdatum (ms) minus Dauer = geschaetzter Start; null ohne Datum. */
    fun fromModified(lastModifiedMs: Long?, durationSec: Double?, zone: ZoneId): ZonedDateTime? {
        if (lastModifiedMs == null || lastModifiedMs <= 0L) return null
        val startMs = lastModifiedMs - ((durationSec ?: 0.0) * 1000.0).toLong()
        val t = java.time.Instant.ofEpochMilli(startMs).atZone(zone)
        return if (t.year in MIN_YEAR..MAX_YEAR) t else null
    }

    /** Beste Quelle nach Vorrang ([TimeSource]-Reihenfolge); null = nichts gefunden. */
    fun best(candidates: List<TimeGuess>): TimeGuess? = candidates.minByOrNull { it.source.ordinal }

    // ── Eingabe im Dialog ────────────────────────────────────────────

    /** Anzeige/Eingabe "yyyy-MM-dd HH:mm:ss" (Ortszeit). */
    fun formatInput(t: ZonedDateTime, zone: ZoneId): String = t.withZoneSameInstant(zone).format(INPUT_FORMAT)

    /** Eingabe "yyyy-MM-dd HH:mm[:ss]" (auch mit T) als Ortszeit; null = ungueltig. */
    fun parseInput(text: String, zone: ZoneId): ZonedDateTime? {
        val v = text.trim().replace('T', ' ')
        val m = Regex("""^(\d{4})-(\d{1,2})-(\d{1,2}) (\d{1,2}):(\d{2})(?::(\d{2}))?$""").find(v) ?: return null
        val g = m.groupValues
        return localOrNull(g[1], g[2], g[3], g[4], g[5], g[6].ifEmpty { "0" })?.atZone(zone)
    }

    // ── Hilfen ───────────────────────────────────────────────────────

    /** ISO-artige Zeit mit oder ohne Offset ("2024-05-01T05:00:00+02:00", "...Z", "2024-05-01 05:00:00"). */
    internal fun parseFlexible(value: String, zone: ZoneId): ZonedDateTime? {
        var v = value.trim()
        if (v.length >= 11 && v[10] == ' ') v = v.substring(0, 10) + "T" + v.substring(11)
        runCatching { return OffsetDateTime.parse(v).atZoneSameInstant(zone).takeIf { it.year in MIN_YEAR..MAX_YEAR } }
        runCatching { return LocalDateTime.parse(v).atZone(zone).takeIf { it.year in MIN_YEAR..MAX_YEAR } }
        return null
    }

    private fun localOrNull(y: String, mo: String, d: String, h: String, mi: String, s: String): LocalDateTime? = try {
        val year = y.toInt()
        if (year !in MIN_YEAR..MAX_YEAR) null
        else LocalDateTime.of(LocalDate.of(year, mo.toInt(), d.toInt()), LocalTime.of(h.toInt(), mi.toInt(), s.toInt()))
    } catch (_: Exception) { null }

    private fun validLatLon(lat: Double, lon: Double) = lat in -90.0..90.0 && lon in -180.0..180.0
}
