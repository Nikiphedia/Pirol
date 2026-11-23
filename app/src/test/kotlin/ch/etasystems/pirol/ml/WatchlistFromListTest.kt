package ch.etasystems.pirol.ml

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** T190: Watchlist aus Artenliste uebernehmen (Zusammenfuehren / Ersetzen). */
class WatchlistFromListTest {

    @Before fun setUp() { SpeciesKey.setAliases(mapOf("Accipiter_gentilis" to "Astur_gentilis")) }
    @After fun tearDown() { SpeciesKey.setAliases(emptyMap()) }

    private val list = CustomSpeciesList(
        "list:x", "Meine Liste", "My list",
        setOf("Turdus_merula", "Parus_major", "Accipiter_gentilis"), "x.json"
    )
    private val current = Watchlist(
        name = "Alt", species = listOf(
            WatchlistEntry("Turdus_merula", "Amsel", WatchlistPriority.high, "Garten"),
            WatchlistEntry("Falco_peregrinus", "Wanderfalke")
        )
    )

    @Test fun `Zusammenfuehren behaelt Bestehendes und haengt Neues an`() {
        val r = WatchlistFromList.apply(current, list, true, { "N-$it" }, "2026-11-23T10:00:00+01:00")
        assertEquals("Alt", r.name)
        assertEquals(4, r.species.size)
        val amsel = r.species.first { it.scientificName == "Turdus_merula" }
        assertEquals(WatchlistPriority.high, amsel.priority)
        assertEquals("Garten", amsel.notes)
        val parus = r.species.first { it.scientificName == "Parus_major" }
        assertEquals("N-Parus_major", parus.commonName)
        assertEquals(WatchlistPriority.normal, parus.priority)
        assertNull(parus.notes)
        assertEquals("2026-11-23T10:00:00+01:00", r.updatedAt)
    }

    @Test fun `Zusammenfuehren erkennt Synonyme ueber SpeciesKey`() {
        val cur = Watchlist(species = listOf(WatchlistEntry("Astur_gentilis")))
        val r = WatchlistFromList.apply(cur, list, true, { "" }, "t")
        assertEquals(3, r.species.size) // Habicht nicht doppelt
    }

    @Test fun `Ersetzen ersetzt die ganze Watchlist`() {
        val r = WatchlistFromList.apply(current, list, false, { "" }, "t")
        assertEquals("Meine Liste", r.name)
        assertEquals(3, r.species.size)
        assertEquals(WatchlistPriority.normal, r.species.first { it.scientificName == "Turdus_merula" }.priority)
    }

    @Test fun `Zusammenfuehren in leere Watchlist uebernimmt Listennamen`() {
        val r = WatchlistFromList.apply(Watchlist(), list, true, { "" }, "t")
        assertEquals("Meine Liste", r.name)
        assertEquals(3, r.species.size)
    }

    @Test fun `Ausgangszustand bleibt fuer Undo unveraendert`() {
        WatchlistFromList.apply(current, list, false, { "" }, "t")
        assertEquals(2, current.species.size)
    }
}
