package ch.etasystems.pirol.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/** T190: Saison-Logik der Artenlisten v4. */
class ListSeasonTest {

    private fun p(from: String, to: String) = ListSeason.parsePeriod(from, to)!!

    @Test fun `normaler Zeitraum inklusiv`() {
        val s = listOf(p("04-15", "08-15"))
        assertTrue(ListSeason.isInSeason(s, 4 to 15))
        assertTrue(ListSeason.isInSeason(s, 8 to 15))
        assertFalse(ListSeason.isInSeason(s, 4 to 14))
        assertFalse(ListSeason.isInSeason(s, 8 to 16))
    }

    @Test fun `Monatsangabe von Monatsanfang bis Monatsende`() {
        val s = listOf(p("05", "06"))
        assertTrue(ListSeason.isInSeason(s, 5 to 1))
        assertTrue(ListSeason.isInSeason(s, 6 to 30))
        assertFalse(ListSeason.isInSeason(s, 7 to 1))
    }

    @Test fun `Jahreswechsel Nov bis Feb`() {
        val s = listOf(p("11", "02"))
        assertTrue(ListSeason.isInSeason(s, 11 to 1))
        assertTrue(ListSeason.isInSeason(s, 12 to 31))
        assertTrue(ListSeason.isInSeason(s, 1 to 15))
        assertTrue(ListSeason.isInSeason(s, 2 to 29))
        assertFalse(ListSeason.isInSeason(s, 3 to 1))
        assertFalse(ListSeason.isInSeason(s, 10 to 31))
    }

    @Test fun `mehrere Zeitraeume sind eine Vereinigung`() {
        val s = listOf(p("03", "04"), p("09", "10"))
        assertTrue(ListSeason.isInSeason(s, 3 to 10))
        assertTrue(ListSeason.isInSeason(s, 10 to 10))
        assertFalse(ListSeason.isInSeason(s, 7 to 10))
    }

    @Test fun `ohne Saison oder ohne Datum ganzjaehrig`() {
        assertTrue(ListSeason.isInSeason(null, 1 to 1))
        assertTrue(ListSeason.isInSeason(emptyList(), 1 to 1))
        assertTrue(ListSeason.isInSeason(listOf(p("05", "06")), null))
    }

    @Test fun `ungueltige Grenzen werden abgelehnt`() {
        assertNull(ListSeason.parsePeriod("13", "02"))
        assertNull(ListSeason.parsePeriod("04-31", "05"))
        assertNull(ListSeason.parsePeriod("04", null))
        assertNull(ListSeason.parsePeriod("4", "5"))
        assertNull(ListSeason.parsePeriod("04-1", "05"))
    }

    @Test fun `Datum in lokaler Zeitzone nicht UTC`() {
        // 2026-04-30T23:30Z = 2026-05-01 01:30 in Zuerich (Sommerzeit)
        val ms = java.time.Instant.parse("2026-04-30T23:30:00Z").toEpochMilli()
        assertEquals(5 to 1, ListSeason.monthDay(ms, ZoneId.of("Europe/Zurich")))
        assertEquals(4 to 30, ListSeason.monthDay(ms, ZoneId.of("UTC")))
    }
}
