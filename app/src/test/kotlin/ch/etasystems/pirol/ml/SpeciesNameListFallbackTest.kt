package ch.etasystems.pirol.ml

import android.content.ContextWrapper
import org.junit.Assert.assertEquals
import org.junit.Test

/** T288: Listenname in der gewaehlten Sprache steht vor Englisch. */
class SpeciesNameListFallbackTest {

    private val sources = listOf(mapOf("en" to "House cricket"))

    @Test fun deutschVorEnglisch() {
        assertEquals("Heimchen", SpeciesNameResolver.pickCommon("Acheta_domesticus", sources, "Heimchen", "de"))
    }

    @Test fun andereSpracheEnglischVorListe() {
        assertEquals("House cricket", SpeciesNameResolver.pickCommon("Acheta_domesticus", sources, "Heimchen", "fr"))
    }

    @Test fun masterDeutschGewinntVorListe() {
        val s = listOf(mapOf("de" to "Hausgrille", "en" to "House cricket"))
        assertEquals("Hausgrille", SpeciesNameResolver.pickCommon("Acheta_domesticus", s, "Heimchen", "de"))
    }

    @Test fun ohneAllesWissenschaftlich() {
        assertEquals("Acheta domesticus", SpeciesNameResolver.pickCommon("Acheta_domesticus", emptyList(), null, "de"))
    }

    @Test fun parseListNamesUndResolver() {
        val r = SpeciesNameResolver(ContextWrapper(null))
        val names = r.parseListNames("""{"sets":[{"id":"a","species":[]},{"id":"b","species_info":{"Acheta_domesticus":{"common_name":"Heimchen"}}}]}""")
        assertEquals(mapOf("Acheta_domesticus" to "Heimchen"), names)
        r.setBuiltinListNames(names)
        r.setMasterNames(mapOf("Acheta_domesticus" to mapOf("en" to "House cricket")))
        assertEquals("Heimchen", r.commonName("Acheta_domesticus"))
        r.language = "en"
        assertEquals("House cricket", r.commonName("Acheta_domesticus"))
    }
}
