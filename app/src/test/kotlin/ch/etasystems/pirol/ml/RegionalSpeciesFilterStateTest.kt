package ch.etasystems.pirol.ml

import android.content.ContextWrapper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Before
    fun setUp() {
        SpeciesKey.setAliases(emptyMap())
        TaxonGroups.setIndex(mapOf("Turdus_merula" to TaxonGroup.BIRDS, "Parus_major" to TaxonGroup.BIRDS))
    }

    @After
    fun tearDown() = TaxonGroups.setIndex(emptyMap())

    @Test
    fun `Leser sehen bei parallelem Regionswechsel nur ganze Zustaende`() {
        val f = filter()
        f.loadRegion("set_a")
        val stop = AtomicBoolean(false)
        val errors = Collections.synchronizedList(mutableListOf<String>())
        val reads = AtomicInteger(0)
        val readers = (1..3).map {
            thread {
                while (!stop.get()) {
                    val s = f.snapshot()
                    val merula = s.isPlausible("Turdus merula", false, null)
                    val major = s.isPlausible("Parus_major", false, null)
                    val ok = when (s.regionId) {
                        "set_a" -> merula && !major && s.species.size == 2
                        "set_b" -> !merula && major && s.species == setOf("Parus_major")
                        else -> false
                    }
                    if (!ok) errors.add("region=${s.regionId} species=${s.species} merula=$merula major=$major")
                    reads.incrementAndGet()
                }
            }
        }
        repeat(3000) { i -> f.loadRegion(if (i % 2 == 0) "set_b" else "set_a") }
        stop.set(true)
        readers.forEach { it.join() }
        assertTrue("gemischte Zustaende: ${errors.take(3)}", errors.isEmpty())
        assertTrue(reads.get() > 0)
    }

    @Test
    fun `Gruppen, Geomodel und Maske parallel - Invarianten halten, keine Maske geht verloren`() {
        val f = filter()
        val stop = AtomicBoolean(false)
        val errors = Collections.synchronizedList(mutableListOf<String>())
        val reader = thread {
            while (!stop.get()) {
                val s = f.snapshot()
                if (s.groupRules != null && (s.regionId != RegionalSpeciesFilter.GROUPS_ID || s.autoGeo || s.custom != null))
                    errors.add("Gruppen gemischt: region=${s.regionId} autoGeo=${s.autoGeo}")
                if (s.autoGeo && (s.regionId != GeoLogic.REGION_ID || s.groupRules != null))
                    errors.add("Geo gemischt: region=${s.regionId} rules=${s.groupRules != null}")
                if (s.regionId == "set_a" && (s.autoGeo || s.groupRules != null))
                    errors.add("Liste gemischt: autoGeo=${s.autoGeo}")
            }
        }
        val start = CountDownLatch(1)
        val loader = thread {
            start.await()
            repeat(1500) { i ->
                f.loadRegion(listOf(RegionalSpeciesFilter.GROUPS_ID, GeoLogic.REGION_ID, "set_a")[i % 3])
            }
        }
        val masker = thread {
            start.await()
            repeat(1500) { f.setGeoMask(GeoMask(setOf("parus_major"), emptySet()), GeoCombine.INTERSECT) }
        }
        start.countDown()
        loader.join(); masker.join()
        stop.set(true); reader.join()
        assertTrue("gemischte Zustaende: ${errors.take(3)}", errors.isEmpty())

        // Nach allen Schreibern: letzte Maske bleibt beim Gruppen- und Geo-Laden erhalten
        val mask = GeoMask(setOf("turdus_merula"), emptySet())
        f.setGeoMask(mask, GeoCombine.UNION)
        f.loadRegion(RegionalSpeciesFilter.GROUPS_ID)
        assertSame(mask, f.snapshot().geoMask)
        assertEquals(GeoCombine.UNION, f.snapshot().geoCombine)
        f.loadRegion(GeoLogic.REGION_ID)
        assertSame(mask, f.snapshot().geoMask)
        // Einzelliste ohne Geomodel verwirft die Maske (wie bisher)
        f.loadRegion("set_a")
        assertNull(f.snapshot().geoMask)
    }

    @Test
    fun `region_sets wird einmal geparst`() {
        val f = filter()
        repeat(20) { f.loadRegion(if (it % 2 == 0) "set_a" else "set_b") }
        f.loadRegion(RegionalSpeciesFilter.GROUPS_ID)
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
        f.loadRegion("ch_breeding")
        assertEquals(f.getAvailableRegions().first { it.id == "ch_breeding" }.speciesCount, f.getSpeciesList().size)
        assertTrue(f.isPlausible("Turdus merula"))
    }

    @Test
    fun `unbekannte Region laesst die Liste stehen (wie bisher)`() {
        val f = filter()
        f.loadRegion("set_b")
        f.loadRegion("gibt_es_nicht")
        assertEquals("set_b", f.getCurrentRegionId())
        assertEquals(setOf("Parus_major"), f.getSpeciesList())
        f.loadRegion("all")
        assertTrue(f.isPlausible("Parus_major") && f.isPlausible("Gryllus campestris"))
    }

    @Test
    fun `Vorschau der Gruppenwahl aendert den aktiven Filter nicht`() {
        val f = filter()
        f.loadRegion("set_b")
        val before = f.snapshot()
        f.previewGroupSpecies()
        assertSame(before, f.snapshot())
        assertFalse(f.isPlausible("Turdus merula"))
    }
}
