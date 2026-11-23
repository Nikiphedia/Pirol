package ch.etasystems.pirol.ml

import android.content.ContextWrapper
import org.junit.Assert.assertEquals
import org.junit.Test

/** T235: Der Schalter "wissenschaftlich anzeigen" aendert nur die Anzeige, nie die Daten. */
class SpeciesNameSeparationTest {

    private fun resolver() = SpeciesNameResolver(ContextWrapper(null)).also {
        it.setListNames(mapOf("Parus_major" to "Kohlmeise"))
    }

    @Test
    fun schalterAus_anzeigeUndDatenSindLandesname() {
        val r = resolver()
        assertEquals("Kohlmeise", r.commonName("Parus_major"))
        assertEquals("Kohlmeise", r.displayName("Parus_major"))
        assertEquals("Parus major", r.subtitle("Parus_major"))
    }

    @Test
    fun schalterAn_datenBleibenLandesname_anzeigeWissenschaftlich() {
        val r = resolver()
        r.scientificNames = true
        assertEquals("Kohlmeise", r.commonName("Parus_major"))
        assertEquals("Parus major", r.displayName("Parus_major"))
        assertEquals("Kohlmeise", r.subtitle("Parus_major"))
        assertEquals(true, r.scientificNamesFlow.value)
    }
}
