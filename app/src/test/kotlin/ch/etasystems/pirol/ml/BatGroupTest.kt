package ch.etasystems.pirol.ml

import ch.etasystems.pirol.rescan.RescanModelOffer
import ch.etasystems.pirol.ui.onboarding.SetupChoices
import ch.etasystems.pirol.ui.onboarding.SetupProfile
import ch.etasystems.pirol.ui.onboarding.SetupPurpose
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/** T295: Artgruppe Fledermaeuse, Liste ch_bats, Pref-Migration, Rescan-Modellangebot. */
class BatGroupTest {

    companion object {
        private val listText = File("src/main/assets/regions/region_sets.json").readText(Charsets.UTF_8)

        @BeforeClass @JvmStatic fun setup() {
            SpeciesKey.setAliases(emptyMap())
            TaxonGroups.setIndex(TaxonGroups.parseRegionSets(listText))
        }

        @AfterClass @JvmStatic fun reset() = TaxonGroups.setIndex(emptyMap())
    }

    @Test fun `Gattungen ordnen Fledermaeuse der Gruppe zu`() {
        assertEquals(TaxonGroup.BATS, TaxonGroups.of("Myotis_myotis"))
        assertEquals(TaxonGroup.BATS, TaxonGroups.of("Rhinolophus euryale"))
        assertEquals(TaxonGroup.BATS, TaxonGroups.of("Tadarida_teniotis"))
        assertEquals(TaxonGroup.OTHER, TaxonGroups.of("Dog"))
        assertEquals(TaxonGroup.BATS, TaxonGroups.fromTaxonGroup("bats"))
    }

    @Test fun `Gattung hat Vorrang vor Mammalia aus species_master`() {
        TaxonGroups.addMasterGroups(mapOf("Rhinolophus_ferrumequinum" to "mammalia", "Vulpes_vulpes" to "mammalia"))
        assertEquals(TaxonGroup.BATS, TaxonGroups.of("Rhinolophus_ferrumequinum"))
        assertEquals(TaxonGroup.MAMMALS, TaxonGroups.of("Vulpes_vulpes"))
    }

    @Test fun `Pref ohne Fledermaus-Gruppe gilt als Alle`() {
        val old = SpeciesGroupSelection.decode("birds=auto_geo\nmammals=off")!!
        assertEquals(GroupSource.ALL, old.sourceOf(TaxonGroup.BATS))
        assertEquals(GroupSource.OFF, old.sourceOf(TaxonGroup.MAMMALS))
        assertEquals(GroupSource.OFF, old.sourceOf(TaxonGroup.AMPHIBIANS))
        assertEquals(GroupSource.ALL, SpeciesGroupSelection.DEFAULT.sourceOf(TaxonGroup.BATS))
        assertEquals(GroupSource.ALL, SpeciesGroupSelection.fromMap(mapOf("birds" to "ch_all")).sourceOf(TaxonGroup.BATS))
    }

    @Test fun `Fledermaus-Auswahl wird gespeichert, Auto gilt als Alle`() {
        val sel = SpeciesGroupSelection.DEFAULT.with(TaxonGroup.BATS, "ch_bats")
        assertEquals("ch_bats", SpeciesGroupSelection.decode(sel.encode())!!.sourceOf(TaxonGroup.BATS))
        val off = SpeciesGroupSelection.DEFAULT.with(TaxonGroup.BATS, GroupSource.OFF)
        assertEquals(GroupSource.OFF, SpeciesGroupSelection.decode(off.encode())!!.sourceOf(TaxonGroup.BATS))
        val auto = SpeciesGroupSelection.DEFAULT.with(TaxonGroup.BATS, GroupSource.AUTO)
        assertEquals(GroupSource.ALL, auto.sourceOf(TaxonGroup.BATS))
        assertEquals("bats", TaxonGroup.BATS.code)
        assertEquals(GroupSource.ALL, auto.toMap()["bats"])
    }

    @Test fun `Liste ch_bats ist gueltig`() {
        val r = CustomSpeciesLists.parse("region_sets.json", listText)
        assertNull(r.error)
        val l = r.lists.first { it.id.endsWith("ch_bats") }
        assertEquals(20, l.species.size)
        assertTrue("Pipistrellus_pipistrellus" in l.species)
        assertTrue("Rhinolophus_euryale" !in l.species)
        assertEquals("chiroptera", l.taxonGroup)
        assertTrue(l.species.all { TaxonGroups.of(it) == TaxonGroup.BATS })
        assertTrue(l.species.none { it.equals("Background", ignoreCase = true) })
    }

    @Test fun `Listennamen deutsch fuer alle Arten`() {
        val sets = Json.parseToJsonElement(listText).jsonObject["sets"] as JsonArray
        val bats = sets.map { it.jsonObject }.first { it["id"]!!.jsonPrimitive.content == "ch_bats" }
        val info = bats["species_info"] as JsonObject
        assertEquals(20, info.size)
        assertTrue(info.values.all { it.jsonObject["common_name"]!!.jsonPrimitive.content.isNotBlank() })
    }

    @Test fun `Einrichtung setzt Fledermaeuse nur bei Forschung`() {
        fun r(vararg p: SetupPurpose) = SetupProfile.resolve(SetupChoices(purposes = p.toSet()), emptyList())
        assertNull(r(SetupPurpose.FIELD_BIRDS).groupSources[TaxonGroup.BATS])
        assertNull(r(SetupPurpose.MONITORING).groupSources[TaxonGroup.BATS])
        assertEquals("all", r(SetupPurpose.RESEARCH).groupSources[TaxonGroup.BATS])
    }

    // --- Rescan-Modellangebot ---

    private fun model(file: String, minRate: Int?): ModelManager.InstalledModel =
        ModelManager.InstalledModel(file, file, 10, false, ModelProfile.V3_FINAL_FP16.copy(minSourceRate = minRate))

    @Test fun `Ultraschall-Modell wird bei kleiner Rate nicht angeboten`() {
        val bird = model("bird.onnx", null)
        val bat = model("bat.onnx", 250_000)
        val low = RescanModelOffer.split(listOf(bird, bat), 48_000)
        assertEquals(listOf(bird), low.offered)
        assertEquals(listOf(bat), low.hidden)
        val high = RescanModelOffer.split(listOf(bird, bat), 384_000)
        assertEquals(listOf(bird, bat), high.offered)
        assertTrue(high.hidden.isEmpty())
        assertEquals(2, RescanModelOffer.split(listOf(bird, bat), 0).offered.size)  // Rate unbekannt
        assertEquals(2, RescanModelOffer.split(listOf(bird, bat), 250_000).offered.size)  // genau Mindestrate
    }

    @Test fun `Standardmodell wechselt auf angebotenes`() {
        val bird = model("bird.onnx", null)
        val bat = model("bat.onnx", 250_000)
        val offered = RescanModelOffer.split(listOf(bird, bat), 48_000).offered
        assertEquals("bird.onnx", RescanModelOffer.defaultModel(offered, "bat.onnx"))
        assertEquals("bird.onnx", RescanModelOffer.defaultModel(offered, "bird.onnx"))
        assertNull(RescanModelOffer.defaultModel(emptyList(), "bird.onnx"))
    }
}
