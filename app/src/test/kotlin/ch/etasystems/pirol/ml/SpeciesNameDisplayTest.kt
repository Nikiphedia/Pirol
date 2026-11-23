package ch.etasystems.pirol.ml

import org.junit.Assert.assertEquals
import org.junit.Test

/** T220: Fallback-Kette der Artnamen (ohne Context). Gruppierung nach Gattung/Familie: Editor-Selbsttest. */
class SpeciesNameDisplayTest {

    @Test
    fun sprachnameVorEnglisch() {
        val names = mapOf("de" to "Kohlmeise", "en" to "Great Tit")
        assertEquals("Kohlmeise", SpeciesNameResolver.pickCommon("Parus_major", names, null, null, "de"))
        assertEquals("Great Tit", SpeciesNameResolver.pickCommon("Parus_major", names, null, null, "fr"))
    }

    @Test
    fun rueckfallAufWissenschaftlichenNamenOhneUnterstrich() {
        assertEquals("Parus major", SpeciesNameResolver.pickCommon("Parus_major", null, null, null, "de"))
    }

    @Test
    fun modellUndListennameAlsRueckfall() {
        assertEquals("Modell", SpeciesNameResolver.pickCommon("X_y", null, mapOf("de" to "Modell"), "Liste", "de"))
        assertEquals("Liste", SpeciesNameResolver.pickCommon("X_y", null, null, "Liste", "de"))
    }
}
