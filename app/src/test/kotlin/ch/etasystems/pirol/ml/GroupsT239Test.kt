package ch.etasystems.pirol.ml

import android.content.ContextWrapper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** T239: Rescan mit vorgegebener Gruppenwahl, Kaltstart-Filter. */
class GroupsT239Test {

    private val json = """
        {"sets":[
          {"id":"ch_all","name_de":"CH+","species":["Turdus_merula"]},
          {"id":"ch_orthoptera_anura","name_de":"HA","species":["Gryllus_campestris"]}
        ]}
    """.trimIndent()

    private fun filter() = RegionalSpeciesFilter(ContextWrapper(null), regionSetsText = { json })

    @Before
    fun setUp() {
        SpeciesKey.setAliases(emptyMap())
        TaxonGroups.setIndex(mapOf(
            "Turdus_merula" to TaxonGroup.BIRDS, "Parus_major" to TaxonGroup.BIRDS,
            "Gryllus_campestris" to TaxonGroup.ORTHOPTERA, "Gryllus_fremd" to TaxonGroup.ORTHOPTERA
        ))
    }

    @After
    fun tearDown() = TaxonGroups.setIndex(emptyMap())

    @Test
    fun `loadGroups nimmt die vorgegebene Wahl statt der Einstellungen`() {
        val f = filter()
        f.loadGroups(SpeciesGroupSelection(mapOf(TaxonGroup.BIRDS to "ch_all", TaxonGroup.ORTHOPTERA to GroupSource.OFF)))
        assertEquals(RegionalSpeciesFilter.GROUPS_ID, f.getCurrentRegionId())
        assertTrue(f.isPlausible("Turdus_merula"))
        assertFalse(f.isPlausible("Parus_major"))          // nicht in CH+
        assertFalse(f.isPlausible("Gryllus_campestris"))   // Heuschrecken Aus
    }

    @Test
    fun `Auto ohne Maske nutzt Ersatzliste je Gruppe, unbekannte Art mit Maske ebenso`() {
        val f = filter()
        f.loadGroups(SpeciesGroupSelection(mapOf(TaxonGroup.ORTHOPTERA to GroupSource.AUTO)))
        // Geomodel kennt keine Heuschrecken -> Ersatzliste ch_orthoptera_anura entscheidet, auch mit Maske
        f.setGeoMask(GeoMask(known = setOf("Turdus_merula"), allowed = setOf("Turdus_merula")), GeoCombine.GEO_ONLY)
        assertTrue(f.isPlausible("Gryllus_campestris"))
        assertFalse(f.isPlausible("Gryllus_fremd"))
        f.setGeoMask(null, GeoCombine.GEO_ONLY)
        assertTrue(f.isPlausible("Gryllus_campestris"))
        assertFalse(f.isPlausible("Gryllus_fremd"))
    }

    @Test
    fun `ensureLoaded laedt beim Kaltstart die Gruppen, danach nicht erneut`() {
        val f = filter()
        assertFalse(f.isLoaded())
        f.ensureLoaded()
        assertTrue(f.isLoaded())
        assertEquals(RegionalSpeciesFilter.GROUPS_ID, f.getCurrentRegionId())
        f.loadRegion("ch_all")
        f.ensureLoaded()  // bereits geladen: unveraendert
        assertEquals("ch_all", f.getCurrentRegionId())
    }
}
