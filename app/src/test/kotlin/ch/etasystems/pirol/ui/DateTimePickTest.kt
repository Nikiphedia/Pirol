package ch.etasystems.pirol.ui

import ch.etasystems.pirol.ui.analysis.DateTimePick
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** T274: Datum + Uhrzeit aus den Pickern, Sekunden bleiben erhalten. */
class DateTimePickTest {
    private val zone = ZoneId.of("Europe/Zurich")

    @Test
    fun combine_keepsSecondsAndUsesLocalZone() {
        val base = ZonedDateTime.of(2026, 5, 1, 5, 0, 37, 0, zone)
        val millis = DateTimePick.dateUtcMillis(ZonedDateTime.of(2026, 6, 15, 23, 30, 0, 0, zone))
        val t = DateTimePick.combine(millis, 7, 45, base, zone)
        assertEquals(ZonedDateTime.of(2026, 6, 15, 7, 45, 37, 0, zone), t)
    }

    @Test
    fun combine_withoutBase_zeroSeconds() {
        val millis = DateTimePick.dateUtcMillis(ZonedDateTime.of(2026, 1, 2, 0, 0, 0, 0, zone))
        assertEquals(ZonedDateTime.of(2026, 1, 2, 12, 5, 0, 0, zone), DateTimePick.combine(millis, 12, 5, null, zone))
    }
}
