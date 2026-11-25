package ch.etasystems.pirol.ml

import android.content.ContextWrapper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T287: Gemischte Artnamen DE/EN. species_master fuehrt manche Arten doppelt (alter und neuer Name);
 * der Eintrag unter dem Modellnamen hat teils nur Englisch (Thinornis_dubius), der Landesname steht
 * unter dem Alias (Charadrius_dubius). Lookup ueber SpeciesKey liefert den Landesnamen.
 */
class SpeciesNameAliasTest {

    @After
    fun tearDown() {
        SpeciesKey.setAliases(emptyMap())
        StoredSpeciesNames.resolver = null
    }

    private fun resolver(): SpeciesNameResolver {
        SpeciesKey.setAliases(mapOf("Charadrius_dubius" to "Thinornis_dubius"))
        return SpeciesNameResolver(ContextWrapper(null)).also {
            it.setMasterNames(
                mapOf(
                    // wie species_master.json (Stand 2026-11): neuer Name ohne de, alter Name mit de
                    "Thinornis_dubius" to mapOf("en" to "Little Ringed Plover"),
                    "Charadrius_dubius" to mapOf("de" to "Flussregenpfeifer", "en" to "Little Ringed Plover"),
                    "Turdus_merula" to mapOf("de" to "Amsel", "en" to "Eurasian Blackbird")
                )
            )
        }
    }

    @Test
    fun umbenannteArt_liefertDeutschenNamen() {
        val r = resolver()
        assertEquals("Flussregenpfeifer", r.commonName("Thinornis_dubius"))
        assertEquals("Flussregenpfeifer", r.commonName("Thinornis dubius"))
        assertEquals("Flussregenpfeifer", r.commonName("Charadrius_dubius"))
        assertEquals("Amsel", r.commonName("Turdus merula"))
    }

    @Test
    fun englischNurWennKeineQuelleDieSpracheHat() {
        val r = resolver()
        r.language = "fr"
        assertEquals("Little Ringed Plover", r.commonName("Thinornis_dubius"))
    }

    @Test
    fun modellnameUeberAlias() {
        val r = resolver()
        // Modell (z.B. 3.0 final) kennt die Art nur unter dem neuen Namen, mit Landesnamen
        r.setModelNames(mapOf("Thinornis_dubius" to mapOf("it" to "Corriere piccolo")))
        r.language = "it"
        assertEquals("Corriere piccolo", r.commonName("Charadrius dubius"))
    }

    @Test
    fun gespeicherterEnglischerName_wirdBeimLesenNeuAufgeloest() {
        val r = resolver()
        StoredSpeciesNames.resolver = r
        assertEquals("Flussregenpfeifer", StoredSpeciesNames.of("Thinornis dubius", "Little Ringed Plover"))
        // Art unbekannt: gespeicherter Name bleibt (kein Rueckfall auf den wiss. Namen)
        assertEquals("Eigenname", StoredSpeciesNames.of("Foo_bar", "Eigenname"))
    }

    @Test
    fun vorDemLaden_bleibtDerGespeicherteName() {
        val r = SpeciesNameResolver(ContextWrapper(null))
        assertEquals(false, r.isLoaded)
        assertEquals("Flussregenpfeifer", r.storedName("Thinornis_dubius", "Flussregenpfeifer"))
        StoredSpeciesNames.resolver = null
        assertEquals("Amsel", StoredSpeciesNames.of("Turdus_merula", "Amsel"))
    }

    @Test
    fun aliasIndex_gruppiertNachSchluessel() {
        val idx = SpeciesNameResolver.buildAliasIndex(listOf("A_b", "C_d", "E_f")) { k ->
            if (k == "C_d") "a_b" else SpeciesKey.normalize(k)
        }
        assertEquals(listOf("A_b", "C_d"), idx["a_b"])
        assertEquals(listOf("E_f"), idx["e_f"])
    }

    @Test
    fun masterNurUnterNeuemNamen_anfrageUnterAltemNamen() {
        SpeciesKey.setAliases(mapOf("Accipiter_gentilis" to "Astur_gentilis"))
        val r = SpeciesNameResolver(ContextWrapper(null))
        r.setMasterNames(mapOf("Astur_gentilis" to mapOf("de" to "Habicht", "en" to "Eurasian Goshawk")))
        assertEquals("Habicht", r.commonName("Accipiter_gentilis"))
    }
}
