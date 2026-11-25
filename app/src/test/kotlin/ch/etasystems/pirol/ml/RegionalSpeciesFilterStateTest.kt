package ch.etasystems.pirol.ml

import android.content.ContextWrapper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * T238: Filterzustand als Schnappschuss — Leser sehen nie einen gemischten Zustand, Laden und
 * Maskenwechsel gehen nicht verloren, region_sets.json wird nur einmal geparst.
 * T246: nur noch Gruppenfilter; Einzellisten wirken als Quelle einer Artgruppe.
 */
class RegionalSpeciesFilterStateTest {

    private val json = """
        {"sets":[
          {"id":"set_a","name_de":"A","species":["Turdus_merula","Erithacus_rubecula"]},
          {"id":"set_b","name_de":"B","species":["Parus_major"]}
        ]}
    """.trimIndent()

    private val parses = AtomicInteger(0)

    private fun filter() = RegionalSpeciesFilter(ContextWrapper(null), regionSetsText = {
        parses.incrementAndGet(); json
    })

    private fun birds(source: String) = SpeciesGroupSelection(mapOf(TaxonGroup.BIRDS to source))

    @Before
    fun setUp() {
        SpeciesKey.setAliases(emptyMap())
        TaxonGroups.setIndex(mapOf(
            "Turdus_merula" to TaxonGroup.BIRDS, "Erithacus_rubecula" to TaxonGroup.BIRDS,
            "Parus_major" to TaxonGroup.BIRDS
        ))
    }

    @After
    fun tearDown() = TaxonGroups.setIndex(emptyMap())

    @Test
    fun `Leser sehen bei parallelem Listenwechsel nur ganze Zustaende`() {
        val f = filter()
        f.loadGroups(birds("set_a"))
        val stop = AtomicBoolean(false)
        val errors = Collections.synchronizedList(mutableListOf<String>())
        val reads = AtomicInteger(0)
        // Schreiben erst, wenn jeder Leser mindestens einen Lesevorgang gemacht hat
        val started = CountDownLatch(3)
        val readers = (1..3).map {
            thread {
                var first = true
                while (!stop.get() || first) {
                    val s = f.snapshot()
                    val merula = s.isPlausible("Turdus merula", false, null)
                    val major = s.isPlausible("Parus_major", false, null)
                    val ok = when (s.species.size) {
                        2 -> merula && !major
                        1 -> !merula && major && s.species == setOf("Parus_major")
                        else -> false
                    }
                    if (!ok) errors.add("species=${s.species} merula=$merula major=$major")
                    reads.incrementAndGet()
                    if (first) {
                        first = false
                        started.countDown()
                    }
                }
            }
        }
        assertTrue("Leser nicht gestartet", started.await(10, java.util.concurrent.TimeUnit.SECONDS))
        repeat(3000) { i -> f.loadGroups(birds(if (i % 2 == 0) "set_b" else "set_a")) }
        stop.set(true)
        readers.forEach { it.join() }
        assertTrue("gemischte Zustaende: ${errors.take(3)}", errors.isEmpty())
        assertTrue(reads.get() >= 3)
    }

    @Test
    fun `Gruppen und Maske parallel - Regeln immer vollstaendig, keine Maske geht verloren`() {
        val f = filter()
        val stop = AtomicBoolean(false)
        val errors = Collections.synchronizedList(mutableListOf<String>())
        val reader = thread {
            while (!stop.get()) {
                val s = f.snapshot()
                val rules = s.groupRules
                if (rules != null && rules.size != TaxonGroup.entries.size)
                    errors.add("Regeln unvollstaendig: ${rules.keys}")
            }
        }
        val start = CountDownLatch(1)
        val loader = thread {
            start.await()
            repeat(1500) { i -> f.loadGroups(birds(listOf(GroupSource.AUTO, "set_a", GroupSource.ALL)[i % 3])) }
        }
        val masker = thread {
            start.await()
            repeat(1500) { f.setGeoMask(GeoMask(setOf("parus_major"), emptySet())) }
        }
        start.countDown()
        loader.join(); masker.join()
        stop.set(true); reader.join()
        assertTrue("gemischte Zustaende: ${errors.take(3)}", errors.isEmpty())

        // Nach allen Schreibern: letzte Maske bleibt beim Laden der Gruppen erhalten
        val mask = GeoMask(setOf("turdus_merula"), emptySet())
        f.setGeoMask(mask)
        f.loadGroups(birds(GroupSource.AUTO))
        assertSame(mask, f.snapshot().geoMask)
        assertTrue(f.isAutoGeo())
        f.loadGroups(birds("set_a"))
        assertSame(mask, f.snapshot().geoMask)
        assertFalse(f.isAutoGeo())
    }

    @Test
    fun `region_sets wird einmal geparst`() {
        val f = filter()
        repeat(20) { f.loadGroups(birds(if (it % 2 == 0) "set_a" else "set_b")) }
        f.getAvailableRegions()
        assertEquals(1, parses.get())
        assertEquals(listOf("set_a", "set_b"), f.getAvailableRegions().map { it.id })
    }

    @Test
    fun `echtes region_sets-Asset wird gelesen`() {
        val text = java.io.File("src/main/assets/regions/region_sets.json").readText(Charsets.UTF_8)
        val f = RegionalSpeciesFilter(ContextWrapper(null), regionSetsText = { text })
        val ids = f.getAvailableRegions().map { it.id }
        assertTrue(ids.toString(), "ch_all" in ids && "ch_breeding" in ids && "ch_orthoptera_anura" in ids)
        f.loadGroups(birds("ch_breeding"))
        assertEquals(f.getAvailableRegions().first { it.id == "ch_breeding" }.speciesCount, f.getSpeciesList().size)
        assertTrue(f.isPlausible("Turdus merula"))
    }

    @Test
    fun `unbekannte Liste filtert die Gruppe nicht, Alle ebenso`() {
        val f = filter()
        f.loadGroups(birds("gibt_es_nicht"))
        assertTrue(f.isPlausible("Parus_major") && f.isPlausible("Turdus_merula"))
        assertTrue(f.getSpeciesList().isEmpty())
        f.loadGroups(birds(GroupSource.ALL))
        assertTrue(f.isPlausible("Parus_major"))
        f.loadGroups(birds(GroupSource.OFF))
        assertFalse(f.isPlausible("Parus_major"))
    }

    @Test
    fun `Vorschau der Gruppenwahl aendert den aktiven Filter nicht`() {
        val f = filter()
        f.loadGroups(birds("set_b"))
        val before = f.snapshot()
        f.previewGroupSpecies()
        assertSame(before, f.snapshot())
        assertFalse(f.isPlausible("Turdus merula"))
    }
}
