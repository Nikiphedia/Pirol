package ch.etasystems.pirol.ml

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** T129: Parsen eigener Artenlisten (lists/NAME.json) und Abgleich ueber SpeciesKey. */
class CustomSpeciesListsTest {

    @Before
    fun setUp() {
        SpeciesKey.setAliases(mapOf("Accipiter_gentilis" to "Astur_gentilis"))
    }

    @After
    fun tearDown() {
        SpeciesKey.setAliases(emptyMap())
    }

    @Test
    fun `einzelnes Set wird geparst`() {
        val r = CustomSpeciesLists.parse(
            "meine.json",
            """{"id":"x","name_de":"Meine","name_en":"Mine","species":["Turdus merula","Parus_major"]}"""
        )
        assertNull(r.error)
        assertEquals(1, r.lists.size)
        val l = r.lists[0]
        assertEquals("list:x", l.id)
        assertEquals("Meine", l.nameDe)
        assertEquals("Mine", l.nameEn)
        assertEquals(setOf("Turdus_merula", "Parus_major"), l.species)
    }

    @Test
    fun `Datei mit mehreren Sets, ungueltiges Set wird uebersprungen`() {
        val r = CustomSpeciesLists.parse(
            "multi.json",
            """{"sets":[{"id":"a","species":["A_b"]},{"id":"b"},{"id":"c","species":["C_d"]}]}"""
        )
        assertNull(r.error)
        assertEquals(listOf("list:a", "list:c"), r.lists.map { it.id })
        assertEquals("a", r.lists[0].nameDe) // Fallback auf id
    }

    @Test
    fun `fehlende id nimmt Dateinamen`() {
        val r = CustomSpeciesLists.parse("wald.json", """{"species":["A_b"]}""")
        assertEquals("list:wald", r.lists.single().id)
    }

    @Test
    fun `ungueltige Dateien liefern Fehler statt Exception`() {
        assertNotNull(CustomSpeciesLists.parse("a.json", "{kaputt").error)
        assertNotNull(CustomSpeciesLists.parse("a.json", "[1,2]").error)
        assertNotNull(CustomSpeciesLists.parse("a.json", """{"id":"x"}""").error)
        assertNotNull(CustomSpeciesLists.parse("a.json", """{"id":"x","species":[]}""").error)
        assertNotNull(CustomSpeciesLists.parse("a.json", """{"sets":5}""").error)
        assertTrue(CustomSpeciesLists.parse("a.json", "{kaputt").lists.isEmpty())
    }

    @Test
    fun `Beispieldatei ist gueltig`() {
        val r = CustomSpeciesLists.parse(CustomSpeciesLists.EXAMPLE_FILE_NAME, CustomSpeciesLists.EXAMPLE_CONTENT)
        assertNull(r.error)
        assertEquals(3, r.lists.single().species.size)
    }

    @Test
    fun `Abgleich ueber Alias und Schreibweise`() {
        val l = CustomSpeciesLists.parse("a.json", """{"id":"x","species":["Accipiter gentilis"]}""").lists.single()
        assertTrue(CustomSpeciesLists.matches(l, "Astur_gentilis"))
        assertTrue(CustomSpeciesLists.matches(l, "accipiter gentilis"))
        assertFalse(CustomSpeciesLists.matches(l, "Parus_major"))
    }

    @Test
    fun signature_erkenntAenderungen() {
        val a = listOf(Triple("a.json", 1L, 10L), Triple("b.json", 2L, 20L))
        assertEquals(CustomSpeciesLists.signature(a), CustomSpeciesLists.signature(a.reversed()))
        assertTrue(CustomSpeciesLists.signature(a) != CustomSpeciesLists.signature(listOf(Triple("a.json", 5L, 10L), a[1])))
        assertTrue(CustomSpeciesLists.signature(a) != CustomSpeciesLists.signature(a.take(1)))
    }
}
