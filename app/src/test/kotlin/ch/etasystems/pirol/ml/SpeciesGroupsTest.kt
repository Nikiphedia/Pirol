package ch.etasystems.pirol.ml

import java.io.File
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/** T232: Artgruppen-Zuordnung, Gruppenregeln, Migration und Kurzanzeige. */
class SpeciesGroupsTest {

    companion object {
        /** Zuordnung aus den echten Assets aufbauen (gleiche Reihenfolge wie TaxonGroups.load) */
        @BeforeClass @JvmStatic fun loadAssets() {
            SpeciesKey.setAliases(emptyMap())
            val idx = HashMap<String, TaxonGroup>()
            TaxonGroups.parseLabelCsv(File("src/main/assets/models/birdnet_v3_labels.csv").readText(Charsets.UTF_8))
                .forEach { (k, v) -> idx.putIfAbsent(k, v) }
            TaxonGroups.parseRegionSets(File("src/main/assets/regions/region_sets.json").readText(Charsets.UTF_8))
                .forEach { (k, v) -> if (v != TaxonGroup.BIRDS || k !in idx) idx[k] = v }
            TaxonGroups.setIndex(idx)
            // species_master wie im SpeciesNameResolver (Name -> taxon_group)
            val taxa = kotlinx.serialization.json.Json.parseToJsonElement(
                File("src/main/assets/species/species_master.json").readText(Charsets.UTF_8))
                .let { (it as kotlinx.serialization.json.JsonObject)["taxa"] as kotlinx.serialization.json.JsonArray }
            TaxonGroups.addMasterGroups(taxa.associate { t ->
                val o = t as kotlinx.serialization.json.JsonObject
                (o["scientific_name"] as kotlinx.serialization.json.JsonPrimitive).content to
                    (o["taxon_group"] as kotlinx.serialization.json.JsonPrimitive).content
            })
        }

        @AfterClass @JvmStatic fun reset() = TaxonGroups.setIndex(emptyMap())
    }

    @Test fun `Gruppenzuordnung aus Assets`() {
        assertEquals(TaxonGroup.BIRDS, TaxonGroups.of("Turdus merula"))                // Amsel
        assertEquals(TaxonGroup.ORTHOPTERA, TaxonGroups.of("Roeseliana_roeselii"))     // Roesels Beissschrecke
        assertEquals(TaxonGroup.ORTHOPTERA, TaxonGroups.of("Gryllus campestris"))
        assertEquals(TaxonGroup.AMPHIBIANS, TaxonGroups.of("Rana_temporaria"))         // Grasfrosch
        assertEquals(TaxonGroup.AMPHIBIANS, TaxonGroups.of("Bufo bufo"))
        assertEquals(TaxonGroup.BATS, TaxonGroups.of("Pipistrellus_pipistrellus"))  // Fledermaus
        assertEquals(TaxonGroup.BATS, TaxonGroups.of("Nyctalus noctula"))
        assertEquals(TaxonGroup.OTHER, TaxonGroups.of("Dog"))
        assertEquals(TaxonGroup.OTHER, TaxonGroups.of("Engine"))
        assertEquals(TaxonGroup.OTHER, TaxonGroups.of("Alligator mississippiensis"))   // Reptil
        // Zikaden (Hemiptera) sind keine Heuschrecken
        val cicada = File("src/main/assets/models/birdnet_v3_labels.csv").readLines()
            .first { it.endsWith(";Insecta;Hemiptera") }.split(';')[2]
        assertEquals(TaxonGroup.OTHER, TaxonGroups.of(cicada))
    }

    @Test fun `Gattung als Rueckfall fuer unbekannte Arten (z B Perch-Labels)`() {
        assertEquals(TaxonGroup.BIRDS, TaxonGroups.of("Turdus fantasia"))
        assertEquals(TaxonGroup.OTHER, TaxonGroups.of("Xyzgenus_novus"))
    }

    @Test fun `classify und fromTaxonGroup`() {
        assertEquals(TaxonGroup.ORTHOPTERA, TaxonGroups.classify("Insecta", "Orthoptera"))
        assertEquals(TaxonGroup.OTHER, TaxonGroups.classify("Insecta", "Hemiptera"))
        assertEquals(TaxonGroup.AMPHIBIANS, TaxonGroups.classify("Amphibia", "Anura"))
        assertEquals(TaxonGroup.BATS, TaxonGroups.classify("Mammalia", "Chiroptera"))
        assertEquals(TaxonGroup.BIRDS, TaxonGroups.classify("Aves", "Passeriformes"))
        assertEquals(TaxonGroup.OTHER, TaxonGroups.classify("Reptilia", null))
        assertNull(TaxonGroups.fromTaxonGroup("insecta"))
        assertEquals(TaxonGroup.AMPHIBIANS, TaxonGroups.fromTaxonGroup("anura"))
        assertEquals(TaxonGroup.BATS, TaxonGroups.fromTaxonGroup("chiroptera"))
        assertEquals(TaxonGroup.OTHER, TaxonGroups.fromTaxonGroup("reptilia"))
    }

    @Test fun `parseLabelCsv mit Komma und ohne class-Spalte`() {
        val m = TaxonGroups.parseLabelCsv("scientific_name,common,class,order\nRana temporaria,Grasfrosch,Amphibia,Anura\n")
        assertEquals(mapOf("rana_temporaria" to TaxonGroup.AMPHIBIANS), m)
        assertTrue(TaxonGroups.parseLabelCsv("idx;sci_name\n0;Turdus merula").isEmpty())
    }

    @Test fun `Gattungsindex nur eindeutig`() {
        val g = TaxonGroups.buildGenusIndex(mapOf(
            "a_x" to TaxonGroup.BIRDS, "a_y" to TaxonGroup.BIRDS,
            "b_x" to TaxonGroup.BIRDS, "b_y" to TaxonGroup.MAMMALS
        ))
        assertEquals(TaxonGroup.BIRDS, g["a"]); assertNull(g["b"])
    }

    // --- Regeln je Gruppe (Aus / Alle / Auto / Liste) ---

    private val mask = GeoMask(known = setOf("a", "b"), allowed = setOf("a"))

    @Test fun `Regel Aus und Alle`() {
        assertFalse(GroupRule.Off.allows("a", mask)); assertFalse(GroupRule.Off.allows("a", null))
        assertTrue(GroupRule.All.allows("b", mask)); assertTrue(GroupRule.All.allows("x", null))
    }

    @Test fun `Regel Auto mit und ohne Maske`() {
        val r = GroupRule.Auto(fallback = setOf("b"))
        assertTrue(r.allows("a", mask)); assertFalse(r.allows("b", mask))
        // T239: vom Geomodel nicht gekannt -> Ersatzliste der Gruppe (nicht "passiert immer")
        assertFalse(r.allows("unbekannt", mask)); assertTrue(GroupRule.Auto(setOf("unbekannt")).allows("unbekannt", mask))
        assertTrue(GroupRule.Auto(emptySet()).allows("unbekannt", mask))  // Saeugetiere: Ersatz = alle
        assertTrue(r.allows("b", null)); assertFalse(r.allows("a", null))  // ohne Maske: Ersatzliste
        assertTrue(GroupRule.Auto(emptySet()).allows("a", null))           // leere Ersatzliste = alle
    }

    @Test fun `Regel Liste`() {
        val r = GroupRule.Listed(setOf("a"))
        assertTrue(r.allows("a", mask)); assertFalse(r.allows("b", null))
        assertTrue(GroupRule.Listed(emptySet()).allows("b", null))  // Liste fehlt -> kein Filter
    }

    // --- Auswahl, Speicherung ---

    @Test fun `Kodierung hin und zurueck, fehlende Gruppe = Aus`() {
        val s = SpeciesGroupSelection(mapOf(TaxonGroup.BIRDS to "auto_geo", TaxonGroup.ORTHOPTERA to "list:meine=liste"))
        val back = SpeciesGroupSelection.decode(s.encode())!!
        assertEquals("auto_geo", back.sourceOf(TaxonGroup.BIRDS))
        assertEquals("list:meine=liste", back.sourceOf(TaxonGroup.ORTHOPTERA))
        assertEquals(GroupSource.OFF, back.sourceOf(TaxonGroup.MAMMALS))
        assertNull(SpeciesGroupSelection.decode(null)); assertNull(SpeciesGroupSelection.decode(""))
        assertTrue(back.usesAuto()); assertFalse(back.with(TaxonGroup.BIRDS, "ch_breeding").usesAuto())
    }

    @Test fun `Kurzanzeige`() {
        val letters = mapOf(TaxonGroup.BIRDS to "V", TaxonGroup.ORTHOPTERA to "H", TaxonGroup.AMPHIBIANS to "A",
            TaxonGroup.MAMMALS to "S", TaxonGroup.BATS to "F", TaxonGroup.OTHER to "O")
        val label: (String) -> String = { when (it) { "off" -> "–"; "auto_geo" -> "Auto"; "ch_orthoptera_anura" -> "CH"; else -> it } }
        val s = SpeciesGroupSelection(mapOf(TaxonGroup.BIRDS to "auto_geo", TaxonGroup.ORTHOPTERA to "ch_orthoptera_anura",
            TaxonGroup.AMPHIBIANS to "ch_orthoptera_anura"))
        assertEquals("V:Auto H:CH A:CH S:– F:–", SpeciesGroupSelection.shortLabel(s, letters, label))
        assertEquals("V:– H:– A:– S:– F:– O:all",
            SpeciesGroupSelection.shortLabel(SpeciesGroupSelection(mapOf(TaxonGroup.OTHER to "all")), letters, label))
    }

    // --- Filterkombinationen je Gruppe (RegionalSpeciesFilter.groupPlausible) ---

    @Test fun `Filterkombinationen je Gruppe`() {
        val groupOf = mapOf("amsel" to TaxonGroup.BIRDS, "kauz" to TaxonGroup.BIRDS, "grille" to TaxonGroup.ORTHOPTERA,
            "frosch" to TaxonGroup.AMPHIBIANS, "fledermaus" to TaxonGroup.MAMMALS, "dog" to TaxonGroup.OTHER)
        val of: (String) -> TaxonGroup = { groupOf[it] ?: TaxonGroup.OTHER }
        val geo = GeoMask(known = setOf("amsel", "kauz", "frosch"), allowed = setOf("amsel"))
        val rules = mapOf(
            TaxonGroup.BIRDS to GroupRule.Auto(setOf("kauz")),
            TaxonGroup.ORTHOPTERA to GroupRule.Listed(setOf("grille")),
            TaxonGroup.AMPHIBIANS to GroupRule.Auto(emptySet()),
            TaxonGroup.MAMMALS to GroupRule.All
            // OTHER fehlt -> Aus
        )
        fun p(k: String, m: GeoMask? = geo) = RegionalSpeciesFilter.groupPlausible(k, rules, m, of)
        assertTrue(p("amsel")); assertFalse(p("kauz"))           // Voegel Auto: Geomodel entscheidet
        assertTrue(p("kauz", null)); assertFalse(p("amsel", null)) // ohne Maske: Ersatzliste
        assertTrue(p("grille")); assertFalse(p("heuschrecke_x"))  // Liste; Unbekanntes = Andere = Aus
        assertFalse(p("frosch"))                                  // Amphibien Auto, Geomodel verneint
        assertTrue(p("fledermaus"))                               // Saeugetiere Alle
        assertFalse(p("dog"))                                     // Andere Aus
        val allOff = mapOf(TaxonGroup.OTHER to GroupRule.All)
        assertTrue(RegionalSpeciesFilter.groupPlausible("dog", allOff, null, of))
        assertFalse(RegionalSpeciesFilter.groupPlausible("amsel", allOff, null, of))
    }

    @Test fun `Ersatzliste je Gruppe`() {
        assertEquals("ch_all", RegionalSpeciesFilter.autoFallbackId(TaxonGroup.BIRDS, "ch_all"))
        assertEquals("ch_orthoptera_anura", RegionalSpeciesFilter.autoFallbackId(TaxonGroup.AMPHIBIANS, "ch_all"))
        assertEquals("all", RegionalSpeciesFilter.autoFallbackId(TaxonGroup.MAMMALS, "ch_all"))
    }

    @Test fun `T246 Ersatzlisten der Auto-Gruppen fuer session json`() {
        val sel = SpeciesGroupSelection(mapOf(TaxonGroup.BIRDS to GroupSource.AUTO, TaxonGroup.ORTHOPTERA to GroupSource.AUTO,
            TaxonGroup.AMPHIBIANS to "ch_orthoptera_anura", TaxonGroup.MAMMALS to GroupSource.AUTO))
        assertEquals(mapOf("birds" to "central_europe", "orthoptera" to "ch_orthoptera_anura", "mammals" to "all"),
            RegionalSpeciesFilter.autoFallbacks(sel, "central_europe"))
        assertEquals(emptyMap<String, String>(),
            RegionalSpeciesFilter.autoFallbacks(SpeciesGroupSelection(mapOf(TaxonGroup.BIRDS to "ch_all")), "ch_all"))
    }

    @Test fun `T239 Vogel-Ersatzliste ist nie die Heuschrecken-Liste`() {
        assertEquals("ch_all", RegionalSpeciesFilter.birdsFallbackId("ch_orthoptera_anura"))
        assertEquals("ch_all", RegionalSpeciesFilter.birdsFallbackId(null))
        assertEquals("central_europe", RegionalSpeciesFilter.birdsFallbackId("central_europe"))
        assertEquals("ch_all", RegionalSpeciesFilter.autoFallbackId(TaxonGroup.BIRDS, "ch_orthoptera_anura"))
    }

    @Test fun `T239 Gruppenwahl aus session json Map`() {
        val sel = SpeciesGroupSelection.fromMap(mapOf("birds" to "ch_all", "orthoptera" to "auto_geo", "xyz" to "all"))
        assertEquals("ch_all", sel.sourceOf(TaxonGroup.BIRDS))
        assertEquals(GroupSource.AUTO, sel.sourceOf(TaxonGroup.ORTHOPTERA))
        assertEquals(GroupSource.OFF, sel.sourceOf(TaxonGroup.MAMMALS))
    }
}
