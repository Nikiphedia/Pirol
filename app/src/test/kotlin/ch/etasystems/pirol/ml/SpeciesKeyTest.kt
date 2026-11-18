package ch.etasystems.pirol.ml

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** T105: Alias-Aufloesung, Regionsfilter und Watchlist ueber den kanonischen Artschluessel. */
class SpeciesKeyTest {

    @Before
    fun setUp() {
        SpeciesKey.setAliases(
            mapOf(
                "Accipiter_gentilis" to "Astur_gentilis",
                "Acanthis_cabaret" to "Acanthis_flammea"
            )
        )
    }

    @After
    fun tearDown() {
        SpeciesKey.setAliases(emptyMap())
    }

    @Test
    fun `alias wird aufgeloest, Normalisierung greift`() {
        assertEquals("astur_gentilis", SpeciesKey.of("Accipiter gentilis"))
        assertEquals("astur_gentilis", SpeciesKey.of(" Astur_gentilis "))
        assertEquals("turdus_merula", SpeciesKey.of("Turdus merula"))
    }

    @Test
    fun `parse liest aliases-Objekt`() {
        val m = SpeciesKey.parse("""{"version":1,"aliases":{"A_b":"C_d"}}""")
        assertEquals(mapOf("a_b" to "c_d"), m)
    }

    @Test
    fun `regionsfilter laesst Habicht unter beiden Namen durch`() {
        val region = RegionalSpeciesFilter.canonicalize(setOf("Accipiter_gentilis", "Turdus_merula"))
        assertTrue(RegionalSpeciesFilter.matches(region, "Astur gentilis"))
        assertTrue(RegionalSpeciesFilter.matches(region, "Accipiter_gentilis"))
        assertFalse(RegionalSpeciesFilter.matches(region, "Parus_major"))
        assertTrue(RegionalSpeciesFilter.matches(emptySet(), "Parus_major"))
    }

    @Test
    fun `regionsfilter Alpenbirkenzeisig ueber Birkenzeisig-Label`() {
        val region = RegionalSpeciesFilter.canonicalize(setOf("Acanthis_flammea"))
        assertTrue(RegionalSpeciesFilter.matches(region, "Acanthis_cabaret"))
    }

    @Test
    fun `watchlist mit altem Namen trifft Detektion mit neuem Label`() {
        val entries = listOf(WatchlistEntry(scientificName = "Accipiter_gentilis", commonName = "Habicht"))
        assertNotNull(WatchlistMatcher.findEntry(entries, "Astur_gentilis"))
        assertNull(WatchlistMatcher.findEntry(entries, "Accipiter_nisus"))
        assertTrue(
            WatchlistManager.matchAndCheckCooldown(
                "Astur_gentilis", setOf(SpeciesKey.of("Accipiter_gentilis")), 0L, emptyMap(), 1000L
            )
        )
    }
}
