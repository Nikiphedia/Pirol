package ch.etasystems.pirol.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** T218: Wochenformel, Maskenlogik, Trigger, Jahresliste des Geomodel-Filters. */
class GeoLogicTest {

    private val id: (String) -> String = { it.lowercase() }

    @Test fun `Wochenformel Monatsgrenzen`() {
        assertEquals(1, GeoLogic.birdnetWeek(1, 1))
        assertEquals(1, GeoLogic.birdnetWeek(1, 7))
        assertEquals(2, GeoLogic.birdnetWeek(1, 8))
        assertEquals(2, GeoLogic.birdnetWeek(1, 14))
        assertEquals(3, GeoLogic.birdnetWeek(1, 15))
        assertEquals(3, GeoLogic.birdnetWeek(1, 21))
        assertEquals(4, GeoLogic.birdnetWeek(1, 22))
        assertEquals(4, GeoLogic.birdnetWeek(1, 31))
        assertEquals(5, GeoLogic.birdnetWeek(2, 1))
        assertEquals(8, GeoLogic.birdnetWeek(LocalDate.of(2026, 2, 28)))
        assertEquals(18, GeoLogic.birdnetWeek(LocalDate.of(2026, 5, 10)))
        assertEquals(19, GeoLogic.birdnetWeek(LocalDate.of(2026, 5, 15)))
        assertEquals(48, GeoLogic.birdnetWeek(LocalDate.of(2026, 12, 31)))
    }

    @Test fun `Maske kennt alle Namen, erlaubt nur ab Schwelle`() {
        val mask = GeoLogic.buildMask(listOf("A_a", "B_b", "C_c"), floatArrayOf(0.5f, 0.02f, 0.03f), 0.03f, id)
        assertEquals(setOf("a_a", "b_b", "c_c"), mask.known)
        assertTrue("a_a" in mask.allowed)
        assertFalse("b_b" in mask.allowed)
        assertTrue("c_c" in mask.allowed)      // genau auf der Schwelle
    }

    @Test fun `Maske kanonisiert ueber Schluessel`() {
        val mask = GeoLogic.buildMask(listOf("Ardea ibis"), floatArrayOf(0.9f), 0.03f) {
            it.trim().replace(' ', '_').lowercase()
        }
        assertTrue("ardea_ibis" in mask.allowed)
    }

    @Test fun `Ortswechsel und Wochenwechsel Trigger`() {
        val last = GeoState(46.95, 7.45, 19, 0L)
        val t = GeoLogic.MIN_INTERVAL_MS
        assertTrue(GeoLogic.shouldRecompute(null, 46.95, 7.45, 19, 0L))
        assertFalse(GeoLogic.shouldRecompute(last, 46.95, 7.45, 19, t * 5))   // nichts geaendert
        assertFalse(GeoLogic.shouldRecompute(last, 46.96, 7.45, 19, t * 5))   // ca. 1 km
        assertTrue(GeoLogic.shouldRecompute(last, 47.10, 7.45, 19, t * 5))    // ca. 17 km
        assertTrue(GeoLogic.shouldRecompute(last, 46.95, 7.45, 20, t * 5))    // neue Woche
        assertFalse(GeoLogic.shouldRecompute(last, 47.10, 7.45, 20, t - 1))   // hoechstens 1x/min
    }

    @Test fun `Abstand ca 111 km pro Breitengrad`() {
        assertEquals(111.2, GeoLogic.distanceKm(46.0, 7.0, 47.0, 7.0), 0.5)
    }

    @Test fun `Jahresliste ist Maximum ueber Wochen`() {
        val weeks = listOf(floatArrayOf(0.1f, 0.9f), floatArrayOf(0.7f, 0.2f), floatArrayOf(0.0f, 0.3f))
        val y = GeoLogic.yearMax(weeks)
        assertEquals(0.7f, y[0], 0f)
        assertEquals(0.9f, y[1], 0f)
        assertEquals(0, GeoLogic.yearMax(emptyList()).size)
    }

    @Test fun `Positionswahl Fix, gespeichert, keine`() {
        val fresh = 1.0 to 2.0
        val saved = 3.0 to 4.0
        val day = 24L * 3600 * 1000
        assertEquals(GeoLogic.PositionSource.GPS, GeoLogic.choosePosition(fresh, saved, 1L, 10 * day).first)
        assertEquals(GeoLogic.PositionSource.SAVED, GeoLogic.choosePosition(null, saved, 5 * day, 10 * day).first)
        assertEquals(GeoLogic.PositionSource.SAVED, GeoLogic.choosePosition(null, saved, 1 * day, 31 * day).first)
        assertEquals(GeoLogic.PositionSource.NONE, GeoLogic.choosePosition(null, saved, 1 * day, 32 * day).first)
        assertEquals(GeoLogic.PositionSource.NONE, GeoLogic.choosePosition(null, null, 0L, 10 * day).first)
        assertNull(GeoLogic.choosePosition(null, null, 0L, 0L).second)
    }
}
