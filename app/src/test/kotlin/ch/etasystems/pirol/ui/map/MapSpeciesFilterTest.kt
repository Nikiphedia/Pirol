package ch.etasystems.pirol.ui.map

import ch.etasystems.pirol.ml.SpeciesKey
import ch.etasystems.pirol.ml.VerificationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MapSpeciesFilterTest {

    private fun m(id: String, sci: String, name: String = sci) = MapViewModel.MapMarker(
        detectionId = id, sessionId = "s", latitude = 47.0, longitude = 8.0,
        species = name, scientificName = sci, confidence = 0.9f, timestampMs = 0L,
        verificationStatus = VerificationStatus.UNVERIFIED
    )

    private val merula = m("1", "Turdus_merula")
    private val major = m("2", "Parus_major")
    private val merula2 = m("3", "Turdus merula")
    private val all = listOf(merula, major, merula2)

    @Test
    fun noFilter_returnsSameList() {
        assertSame(all, MapSpeciesFilter.filter(all, emptySet(), false, emptySet()))
    }

    @Test
    fun hiddenSpecies_areRemoved_andSpaceFormatMatches() {
        val key = SpeciesKey.of("Turdus_merula")
        val r = MapSpeciesFilter.filter(all, setOf(key), false, emptySet())
        assertEquals(listOf(major), r)
    }

    @Test
    fun newSpecies_isVisibleByDefault() {
        val key = SpeciesKey.of("Turdus_merula")
        val neu = m("4", "Erithacus_rubecula")
        val r = MapSpeciesFilter.filter(all + neu, setOf(key), false, emptySet())
        assertEquals(listOf(major, neu), r)
    }

    @Test
    fun onlyAlarm_keepsWatchedOnly() {
        val watched = setOf(SpeciesKey.of("Parus_major"))
        assertEquals(listOf(major), MapSpeciesFilter.filter(all, emptySet(), true, watched))
        assertTrue(MapSpeciesFilter.filter(all, emptySet(), true, emptySet()).isEmpty())
    }

    @Test
    fun onlyAlarm_usesAliasKey() {
        SpeciesKey.setAliases(mapOf("old_name" to "new_name"))
        try {
            val old = m("5", "Old_name")
            val watched = setOf(SpeciesKey.of("New name"))
            assertEquals(listOf(old), MapSpeciesFilter.filter(listOf(old, major), emptySet(), true, watched))
        } finally {
            SpeciesKey.setAliases(emptyMap())
        }
    }

    @Test
    fun combination_hiddenAndAlarm() {
        val watched = setOf(SpeciesKey.of("Parus_major"), SpeciesKey.of("Turdus_merula"))
        val r = MapSpeciesFilter.filter(all, setOf(SpeciesKey.of("Turdus_merula")), true, watched)
        assertEquals(listOf(major), r)
    }

    @Test
    fun speciesEntries_countAndOrder() {
        val e = MapSpeciesFilter.speciesEntries(all)
        assertEquals(listOf("turdus_merula", "parus_major"), e.map { it.key })
        assertEquals(listOf(2, 1), e.map { it.count })
    }

    @Test
    fun toggle_addsAndRemoves() {
        val h = MapSpeciesFilter.toggle(emptySet(), "a")
        assertEquals(setOf("a"), h)
        assertEquals(emptySet<String>(), MapSpeciesFilter.toggle(h, "a"))
    }
}
