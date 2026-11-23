package ch.etasystems.pirol.ml

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** T190: Artenlisten v4 (Saison, Tiergruppe, Namen), v3-Rueckfall, Nicht-Voegel. */
class CustomSpeciesListsV4Test {

    @Before fun setUp() { SpeciesKey.setAliases(emptyMap()) }
    @After fun tearDown() { SpeciesKey.setAliases(emptyMap()) }

    private val v4 = """{
      "version": 4, "author": "N. N.", "description": "Test",
      "id": "t", "name_de": "Test", "taxon_group": "aves",
      "species": ["Oriolus_oriolus", "Turdus_merula", "Chorthippus_biguttulus"],
      "species_info": {
        "Oriolus_oriolus": {"common_name": "Pirol", "season": [{"from":"04-20","to":"08-31"}]},
        "Chorthippus_biguttulus": {"common_name": "Nachtigall-Grashuepfer", "taxon_group": "orthoptera"}
      }
    }"""

    @Test fun `v4 mit Saison, Namen und Kopfdaten`() {
        val l = CustomSpeciesLists.parse("t.json", v4).lists.single()
        assertEquals("N. N.", l.author)
        assertEquals("aves", l.taxonGroup)
        assertEquals(3, l.species.size)
        assertEquals("Pirol", l.commonNames["Oriolus_oriolus"])
        assertEquals(1, l.seasons.size)
        assertTrue(l.hasSeasons())
        assertEquals(listOf(SeasonPeriod(4, 20, 8, 31)), l.seasons[SpeciesKey.of("Oriolus_oriolus")])
    }

    @Test fun `v4 ohne Saison und ohne species_info`() {
        val r = CustomSpeciesLists.parse("t.json", """{"version":4,"id":"x","species":["Parus_major"]}""")
        val l = r.lists.single()
        assertFalse(l.hasSeasons())
        assertTrue(l.commonNames.isEmpty())
        assertEquals("aves", l.taxonGroup)
        assertTrue(r.warnings.isEmpty())
    }

    @Test fun `v3 ohne version bleibt lesbar`() {
        val l = CustomSpeciesLists.parse("a.json", """{"id":"x","name_de":"X","species":["Parus_major"]}""").lists.single()
        assertEquals(setOf("Parus_major"), l.species)
        assertFalse(l.hasSeasons())
    }

    @Test fun `Tiergruppe der Liste wird gelesen`() {
        val l = CustomSpeciesLists.parse("a.json", """{"id":"x","taxon_group":"orthoptera","species":["Chorthippus_biguttulus"]}""").lists.single()
        assertEquals("orthoptera", l.taxonGroup)
    }

    @Test fun `Heuschrecken passieren den Regionsfilter`() {
        val l = CustomSpeciesLists.parse("t.json", v4).lists.single()
        assertTrue(CustomSpeciesLists.matches(l, "Chorthippus_biguttulus"))
        assertTrue(CustomSpeciesLists.matches(l, "Chorthippus biguttulus"))
        assertFalse(CustomSpeciesLists.matches(l, "Gryllus_campestris"))
    }

    @Test fun `Saison wirkt nur bei eingeschaltetem Schalter`() {
        val l = CustomSpeciesLists.parse("t.json", v4).lists.single()
        val winter = 1 to 15
        val juni = 6 to 1
        // Schalter aus: Saison ignoriert
        assertTrue(CustomSpeciesLists.isPlausible(l, "Oriolus_oriolus", false, winter))
        // Schalter an
        assertFalse(CustomSpeciesLists.isPlausible(l, "Oriolus_oriolus", true, winter))
        assertTrue(CustomSpeciesLists.isPlausible(l, "Oriolus_oriolus", true, juni))
        // Art ohne Saison: ganzjaehrig
        assertTrue(CustomSpeciesLists.isPlausible(l, "Turdus_merula", true, winter))
        // Art nicht in Liste: nie
        assertFalse(CustomSpeciesLists.isPlausible(l, "Parus_major", false, juni))
    }

    @Test fun `ungueltige Saison gibt Warnung und Art bleibt ganzjaehrig`() {
        val r = CustomSpeciesLists.parse("t.json",
            """{"version":4,"id":"x","species":["Parus_major"],"species_info":{"Parus_major":{"season":[{"from":"13","to":"02"}]}}}""")
        assertTrue(r.warnings.any { it.contains("Saison") })
        val l = r.lists.single()
        assertNull(l.seasons[SpeciesKey.of("Parus_major")])
        assertTrue(CustomSpeciesLists.isPlausible(l, "Parus_major", true, 7 to 7))
    }

    @Test fun `species_info ohne Eintrag in species wird mit Warnung ignoriert`() {
        val r = CustomSpeciesLists.parse("t.json",
            """{"version":4,"id":"x","species":["Parus_major"],"species_info":{"Turdus_merula":{"season":[{"from":"01","to":"02"}]}}}""")
        assertTrue(r.warnings.isNotEmpty())
        assertTrue(r.lists.single().seasons.isEmpty())
    }

    @Test fun `Unterart wird auf Art abgebildet`() {
        val r = CustomSpeciesLists.parse("t.json", """{"id":"x","species":["Motacilla_flava_flava","Motacilla flava"]}""")
        assertEquals(setOf("Motacilla_flava"), r.lists.single().species)
        assertTrue(r.warnings.any { it.contains("Unterart") })
    }

    @Test fun `neuere Version gibt Hinweis`() {
        val r = CustomSpeciesLists.parse("t.json", """{"version":5,"id":"x","species":["Parus_major"]}""")
        assertTrue(r.warnings.any { it.contains("neuere") })
        assertEquals(1, r.lists.size)
    }

    @Test fun `Beispielliste ist v4 mit Saison und Heuschrecke`() {
        val r = CustomSpeciesLists.parse(CustomSpeciesLists.EXAMPLE_FILE_NAME, CustomSpeciesLists.EXAMPLE_CONTENT)
        assertNull(r.error)
        assertTrue(r.warnings.isEmpty())
        val l = r.lists.single()
        assertTrue(CustomSpeciesLists.EXAMPLE_CONTENT.contains("\"version\": 4"))
        assertEquals(2, l.seasons.size)
        assertTrue(l.species.contains("Chorthippus_biguttulus"))
    }
}
