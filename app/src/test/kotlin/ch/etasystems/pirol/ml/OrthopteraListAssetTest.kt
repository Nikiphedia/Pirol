package ch.etasystems.pirol.ml

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T228: mitgelieferte Liste "CH Heuschrecken & Amphibien" in region_sets.json ist gueltig (Parser T190). */
class OrthopteraListAssetTest {

    private val text = File("src/main/assets/regions/region_sets.json").readText(Charsets.UTF_8)

    @Test fun `Liste ist gueltig und enthaelt Heuschrecken und Froschlurche`() {
        val r = CustomSpeciesLists.parse("region_sets.json", text)
        assertNull(r.error)
        val l = r.lists.first { it.id.endsWith("ch_orthoptera_anura") }
        assertTrue(l.species.size >= 50)
        assertTrue("Gryllus_campestris" in l.species || "Chorthippus_biguttulus" in l.species)
        assertTrue("Rana_temporaria" in l.species)
        assertTrue("Bufo_bufo" in l.species)
        assertTrue(l.seasons.isNotEmpty())
        // beide Gruppen vertreten (taxon_group je Art in species_info)
        val set = (Json.parseToJsonElement(text).jsonObject["sets"] as JsonArray)
            .map { it.jsonObject }.first { it["id"]!!.jsonPrimitive.content == "ch_orthoptera_anura" }
        val groups = set["species_info"]!!.jsonObject.values.map { it.jsonObject["taxon_group"]!!.jsonPrimitive.content }
        assertTrue(groups.count { it == "orthoptera" } >= 50)
        assertTrue(groups.count { it == "anura" } >= 8)
    }

    @Test fun `Bestandslisten bleiben erhalten`() {
        val ids = CustomSpeciesLists.parse("region_sets.json", text).lists.map { it.id }
        assertTrue(ids.any { it.endsWith("ch_breeding") } && ids.any { it.endsWith("ch_all") } && ids.any { it.endsWith("central_europe") })
    }
}
