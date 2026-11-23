package ch.etasystems.pirol.ui

import ch.etasystems.pirol.ui.analysis.SpeciesSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeciesSearchTest {
    @Test fun leererTextPasstImmer() {
        assertTrue(SpeciesSearch.matches("", "Kohlmeise", "Parus major"))
        assertTrue(SpeciesSearch.matches("   ", "Kohlmeise", "Parus major"))
    }

    @Test fun teilwortGrossKleinEgal() {
        assertTrue(SpeciesSearch.matches("mei", "Kohlmeise", "Parus_major"))
        assertTrue(SpeciesSearch.matches("KOHL", "Kohlmeise", "Parus_major"))
        assertFalse(SpeciesSearch.matches("amsel", "Kohlmeise", "Parus_major"))
    }

    @Test fun wissenschaftlicherNameMitUnterstrichOderLeerzeichen() {
        assertTrue(SpeciesSearch.matches("parus maj", "Kohlmeise", "Parus_major"))
        assertTrue(SpeciesSearch.matches("parus_maj", "Kohlmeise", "Parus major"))
        assertTrue(SpeciesSearch.matches("MAJOR", "Kohlmeise", "Parus_major"))
    }

    @Test fun umlauteNormal() {
        assertTrue(SpeciesSearch.matches("wür", "Würger", "Lanius_collurio"))
        assertTrue(SpeciesSearch.matches("WÜR", "Würger", "Lanius_collurio"))
    }

    @Test fun trefferzaehlung() {
        val names = listOf("Parus_major", "Turdus_merula", "Parus_major")
        assertEquals(2, SpeciesSearch.countMatches("meise", names) { if (it.startsWith("Parus")) "Kohlmeise" else "Amsel" })
        assertEquals(3, SpeciesSearch.countMatches("", names) { it })
    }
}
