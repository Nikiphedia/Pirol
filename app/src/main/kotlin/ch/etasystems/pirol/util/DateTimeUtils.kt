package ch.etasystems.pirol.util

import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * ISO-8601-Timestamp-Parsing mit Fallback fuer Offset-Stempel (z.B. +02:00).
 *
 * `Instant.parse()` akzeptiert nur UTC-Stempel mit 'Z'-Suffix.
 * Stempel mit Offset ("+02:00") werden von `OffsetDateTime.parse()` korrekt gelesen.
 *
 * @param value ISO-8601-String, z.B. "2026-04-20T08:30:00Z" oder "2026-04-20T08:30:00+02:00"
 * @return geparster Instant, oder null falls kein Format passt
 */
fun parseInstantCompat(value: String): Instant? = try {
    OffsetDateTime.parse(value).toInstant()
} catch (_: DateTimeParseException) {
    try {
        Instant.parse(value)
    } catch (_: Exception) {
        null
    }
}
