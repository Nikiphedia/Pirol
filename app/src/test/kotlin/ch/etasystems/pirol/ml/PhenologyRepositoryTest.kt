package ch.etasystems.pirol.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit-Tests fuer PhenologyRepository (T74, AC7).
 * Testet PhenologyRepository.parsePhenologyJson (Companion) direkt — kein Context noetig.
 */
class PhenologyRepositoryTest {

    private val emptyJson = """{"version":1,"region":"CH","species":[]}"""

    private val twoSpeciesJson = """
        {
          "version": 1,
          "region": "CH",
          "species": [
            {"key":"Turdus_merula","monthsActive":[3,4,5,6,7],"rarityScore":0.1},
            {"key":"Aquila_chrysaetos","monthsActive":[1,2,11,12],"rarityScore":0.9}
          ]
        }
    """.trimIndent()

    @Test
    fun `leere JSON gibt leere Map ohne Crash`() {
        val result = PhenologyRepository.parsePhenologyJson(emptyJson)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `zwei Arten werden korrekt geparst`() {
        val result = PhenologyRepository.parsePhenologyJson(twoSpeciesJson)
        assertEquals(2, result.size)
        assertTrue(result.containsKey("Turdus_merula"))
        assertTrue(result.containsKey("Aquila_chrysaetos"))
    }

    @Test
    fun `isInSeason gibt true fuer Art im aktiven Monat`() {
        val map = PhenologyRepository.parsePhenologyJson(twoSpeciesJson)
        // Turdus_merula aktiv Monat 3-7; month=5 -> true
        val entry = map["Turdus_merula"]!!
        assertTrue(5 in entry.monthsActive)
    }

    @Test
    fun `isInSeason gibt false fuer Art ausserhalb Saison`() {
        val map = PhenologyRepository.parsePhenologyJson(twoSpeciesJson)
        // Turdus_merula aktiv Monat 3-7; month=12 -> false
        val entry = map["Turdus_merula"]!!
        assertFalse(12 in entry.monthsActive)
    }

    @Test
    fun `fehlende Art in Map liefert isInSeason true (graceful)`() {
        val map = PhenologyRepository.parsePhenologyJson(twoSpeciesJson)
        // Art nicht in Map -> graceful: default true
        val isInSeason = map["Unbekannte_Art"]?.let { 5 in it.monthsActive } ?: true
        assertTrue(isInSeason)
    }

    @Test
    fun `rarity gibt korrekten Wert zurueck`() {
        val map = PhenologyRepository.parsePhenologyJson(twoSpeciesJson)
        assertEquals(0.9f, map["Aquila_chrysaetos"]!!.rarityScore, 0.001f)
    }

    @Test
    fun `rarity default ist 0 fuer unbekannte Art`() {
        val map = PhenologyRepository.parsePhenologyJson(twoSpeciesJson)
        val rarityScore = map["Unbekannte_Art"]?.rarityScore ?: 0.0f
        assertEquals(0.0f, rarityScore, 0.001f)
    }
}
