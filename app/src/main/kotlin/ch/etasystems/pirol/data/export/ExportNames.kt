package ch.etasystems.pirol.data.export

import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.TimeZone

/**
 * T233: Dateinamen beim Teilen/Export/Upload (rein, ohne Android-Abhaengigkeit).
 *
 * - Detektion: `YYYYMMDDHHMM_Art_Bemerkung`
 * - Session (ZIP, Raven): `YYYYMMDDHHMM_Bemerkung`
 * Fehlende Bemerkung: Teil entfaellt. Dateinamen im Session-Ordner bleiben unveraendert.
 *
 * Umlaute bleiben erhalten (UTF-8): SAF/Drive, SMB und Android-Dateisystem kommen damit zurecht;
 * nur Zeichen, die FAT/SMB/SAF verbieten, werden ersetzt.
 */
object ExportNames {

    const val MAX_NOTE_LENGTH = 40
    const val MAX_SPECIES_LENGTH = 60

    private val TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmm")
    private val FORBIDDEN = Regex("""[\\/:*?"<>|\p{Cntrl}\s]+""")

    /** Dateisicherer Teil: verbotene Zeichen und Leerzeichen -> '_', zusammengefasst, gekuerzt. */
    fun sanitizePart(text: String?, maxLength: Int): String {
        if (text.isNullOrBlank()) return ""
        return text.trim()
            .replace(FORBIDDEN, "_")
            .replace(Regex("_+"), "_")
            .trim('_', '.')
            .take(maxLength)
            .trim('_', '.')
    }

    /** `YYYYMMDDHHMM` in lokaler Zeit. */
    fun timePart(timestampMs: Long, timeZone: TimeZone = TimeZone.getDefault()): String =
        TIME_FORMAT.format(java.time.Instant.ofEpochMilli(timestampMs).atZone(timeZone.toZoneId()))

    /** Name ohne Endung: `YYYYMMDDHHMM_Art_Bemerkung`. */
    fun detectionName(
        timestampMs: Long,
        speciesName: String,
        note: String?,
        timeZone: TimeZone = TimeZone.getDefault()
    ): String = join(
        timePart(timestampMs, timeZone),
        sanitizePart(speciesName, MAX_SPECIES_LENGTH),
        sanitizePart(note, MAX_NOTE_LENGTH)
    )

    /**
     * Name ohne Endung: `YYYYMMDDHHMM_Bemerkung`. [startedAt] ist ISO 8601 mit Offset (lokale Zeit
     * der Aufnahme); ohne lesbare Zeit Rueckfall auf [fallbackId].
     */
    fun sessionName(startedAt: String, note: String?, fallbackId: String): String {
        val time = try {
            TIME_FORMAT.format(OffsetDateTime.parse(startedAt))
        } catch (e: Exception) {
            try {
                TIME_FORMAT.format(java.time.Instant.parse(startedAt).atZone(ZoneId.systemDefault()))
            } catch (e2: Exception) {
                sanitizePart(fallbackId, MAX_SPECIES_LENGTH).ifEmpty { "session" }
            }
        }
        return join(time, sanitizePart(note, MAX_NOTE_LENGTH))
    }

    fun sessionZipName(startedAt: String, note: String?, sessionId: String): String =
        sessionName(startedAt, note, sessionId) + ".zip"

    /** Raven-Datei beim Export/Teilen (nicht die kanonische Datei im Session-Ordner). */
    fun sessionRavenName(startedAt: String, note: String?, sessionId: String): String =
        sessionName(startedAt, note, sessionId) + ".selections.txt"

    /** Haengt bei Kollision `-2`, `-3` ... vor die Endung. */
    fun uniqueName(baseName: String, extension: String, exists: (String) -> Boolean): String {
        var candidate = "$baseName.$extension"
        var n = 2
        while (exists(candidate)) candidate = "$baseName-${n++}.$extension"
        return candidate
    }

    /** Gemerkten Namen weiterverwenden (Retry), sonst erster freier Name im Ziel. */
    fun chooseZipName(stored: String?, baseName: String, exists: (String) -> Boolean): String =
        stored ?: uniqueName(baseName, "zip", exists)

    private fun join(vararg parts: String): String = parts.filter { it.isNotEmpty() }.joinToString("_")
}
