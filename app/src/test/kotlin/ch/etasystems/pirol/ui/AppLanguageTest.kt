package ch.etasystems.pirol.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** T120a: Abbildung App-Sprache -> Artnamen-Sprache */
class AppLanguageTest {

    @Test
    fun mundartFaelltFuerArtnamenAufDeutschZurueck() {
        assertEquals("de", AppLanguage.speciesLanguageForTag("gsw-CH", "en"))
        assertEquals("de", AppLanguage.speciesLanguageForTag("gsw", "en"))
    }

    @Test
    fun systemsprache_nutztSystemLocale() {
        assertEquals("fr", AppLanguage.speciesLanguageForTag("", "fr"))
        assertEquals("fr", AppLanguage.speciesLanguageForTag(AppLanguage.SYSTEM_TAG, "FR"))
    }

    @Test
    fun systemsprache_mundartAlsSystemLocale() {
        assertEquals("de", AppLanguage.speciesLanguageForTag("", "gsw"))
    }

    @Test
    fun leereSystemspracheFaelltAufEnglisch() {
        assertEquals("en", AppLanguage.speciesLanguageForTag("", ""))
    }

    @Test
    fun explizitesTagWirdUebernommen() {
        assertEquals("ca", AppLanguage.speciesLanguageForTag("ca", "de"))
        assertEquals("pt", AppLanguage.speciesLanguageForTag("pt-BR", "de"))
    }

    @Test
    fun normalizeTagReduziertRegion() {
        assertEquals("de", AppLanguage.normalizeTag("de-CH"))
        assertEquals("gsw-CH", AppLanguage.normalizeTag("gsw-CH"))
        assertEquals("", AppLanguage.normalizeTag(""))
    }

    @Test
    fun explizitGesetzteArtnamenSpracheHatVorrang() {
        assertEquals("it", AppLanguage.effectiveSpeciesLanguage(true, "it", "en", "de"))
    }

    @Test
    fun nichtExplizitFolgtDerAppSprache() {
        assertEquals("en", AppLanguage.effectiveSpeciesLanguage(false, "it", "en", "de"))
        assertEquals("de", AppLanguage.effectiveSpeciesLanguage(false, "it", "gsw-CH", "en"))
        assertEquals("de", AppLanguage.effectiveSpeciesLanguage(false, "de", "", "de"))
    }
}
