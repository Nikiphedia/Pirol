package ch.etasystems.pirol.ml

import android.content.ContextWrapper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * T238 (#13): Der Regionsfilter wirkt vor der TopK-Auswahl. Laute ausgeschlossene Arten (Grillen)
 * verdraengen keine plausiblen Arten mehr; gekappt wird nach dem Filter auf topK*2.
 */
class RegionFilterBeforeTopKTest {

    private val crickets = (1..6).map { "Cricket_$it" to 0.95f - it * 0.05f }       // 0.90 .. 0.65
    private val birds = (1..8).map { "Bird_$it" to 0.60f - it * 0.03f }             // 0.57 .. 0.36

    /** Classifier, der wie BirdNET erst sortiert und dann auf topK kappt */
    private class RankedClassifier(private val ranked: List<Pair<String, Float>>) : AudioClassifier {
        var lastTopK = -1
        override fun isModelAvailable() = true
        override fun classify(samples: FloatArray, sampleRate: Int, topK: Int, threshold: Float): List<AudioClassifier.ClassifierResult> {
            lastTopK = topK
            return ranked.filter { it.second >= threshold }.sortedByDescending { it.second }.take(topK)
                .map { AudioClassifier.ClassifierResult(it.first, it.first, it.second, 0f, 3f) }
        }
        override fun extractEmbedding(samples: FloatArray, sampleRate: Int): FloatArray? = null
        override fun resetSession() {}
        override val labelCount = 14
        override val modelName = "ranked"
    }

    private val json = """{"sets":[{"id":"birds","species":[${(1..8).joinToString { "\"Bird_$it\"" }}]}]}"""

    @Before
    fun setUp() = SpeciesKey.setAliases(emptyMap())

    @After
    fun tearDown() = TaxonGroups.setIndex(emptyMap())

    private fun run(config: InferenceConfig): Pair<List<DetectionResult>, Int> {
        val filter = RegionalSpeciesFilter(ContextWrapper(null), regionSetsText = { json })
        filter.loadRegion("birds")
        val cls = RankedClassifier(crickets + birds)
        val worker = InferenceWorker(cls, filter, config, onAudioPersist = { null }) { _, _ -> }
        val window = AnalysisWindow(ShortArray(96_000), 32_000, 0L)
        return worker.analyzeWindowNow(window, 0L, 0f) to cls.lastTopK
    }

    @Test
    fun `plausible Arten bleiben trotz lauter Grillen`() {
        val config = InferenceConfig(topK = 3, confidenceThreshold = 0.3f, regionFilter = "birds",
            showOnlyFiltered = true, highpassEnabled = false)
        val (dets, topK) = run(config)
        assertEquals(Int.MAX_VALUE, topK)
        // topK*2 = 6, die besten sechs Voegel in Reihenfolge
        assertEquals((1..6).map { "Bird_$it" }, dets.map { it.scientificName })
    }

    @Test
    fun `ohne Filter unveraendert topK mal 2 vom Classifier`() {
        val config = InferenceConfig(topK = 3, confidenceThreshold = 0.3f, regionFilter = null, highpassEnabled = false)
        val (dets, topK) = run(config)
        assertEquals(6, topK)
        assertEquals(crickets.map { it.first }, dets.map { it.scientificName })
    }

    @Test
    fun `Filter nur protokollierend - Classifier-Kappung wie bisher`() {
        val config = InferenceConfig(topK = 2, confidenceThreshold = 0.3f, regionFilter = "birds",
            showOnlyFiltered = false, highpassEnabled = false)
        val (dets, topK) = run(config)
        assertEquals(4, topK)
        assertEquals(crickets.take(4).map { it.first }, dets.map { it.scientificName })
    }
}
