package ch.etasystems.pirol.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit-Tests fuer Multi-Label-Inference (T71: Block B2).
 *
 * Prueft BirdNetV3Classifier.filterAndRank() mit synthetischen Score-Vektoren:
 * - Threshold-Filterung (N Arten ueber Threshold → N Resultate)
 * - TopK-Begrenzung (TopK=1 = Backward-Kompat, TopK<Treffer = Kappung)
 * - Edge Cases (leere Liste, genau 0 Arten)
 *
 * Kein ONNX-Modell noetig — filterAndRank() ist reine Logik.
 */
class MultiLabelInferenceTest {

    /** Synthetische Label-Liste: 10 Eintraege */
    private val LABELS = (0..9).map { "Species$it" to "Art $it" }

    /**
     * Erzeugt FloatArray der Laenge 10, wobei die uebergebenen Werte ab Index 0 eingesetzt werden.
     * Alle nicht-angegebenen Positionen bleiben 0.0f.
     */
    private fun scores(vararg values: Float): FloatArray {
        val arr = FloatArray(10) { 0f }
        values.forEachIndexed { i, v -> arr[i] = v }
        return arr
    }

    // --- Tests ---

    @Test
    fun `3 arten ueber threshold ergeben 3 resultate`() {
        val s = scores(0.8f, 0.6f, 0.4f, 0.1f)  // 3 ueber 0.3, 1 drunter
        val results = BirdNetV3Classifier.filterAndRank(s, LABELS, topK = 5, threshold = 0.3f)

        assertEquals("Genau 3 Arten ueber Threshold erkannt", 3, results.size)
        assertEquals("Hoechste Konfidenz zuerst (absteigende Sortierung)",
            "Species0", results[0].scientificName)
        assertEquals("Zweite Art korrekt", "Species1", results[1].scientificName)
        assertEquals("Dritte Art korrekt", "Species2", results[2].scientificName)
    }

    @Test
    fun `1 art ueber threshold ergibt 1 resultat`() {
        val s = scores(0.9f, 0.2f, 0.1f)  // nur Species0 ueber 0.3
        val results = BirdNetV3Classifier.filterAndRank(s, LABELS, topK = 5, threshold = 0.3f)

        assertEquals("Genau 1 Art erkannt", 1, results.size)
        assertEquals("Species0 ist das Ergebnis", "Species0", results[0].scientificName)
        assertEquals("Konfidenz korrekt", 0.9f, results[0].confidence, 0.001f)
    }

    @Test
    fun `0 arten ueber threshold ergibt leere liste`() {
        val s = scores(0.1f, 0.2f, 0.29f)  // alle unter 0.3
        val results = BirdNetV3Classifier.filterAndRank(s, LABELS, topK = 5, threshold = 0.3f)

        assertTrue("Leere Liste wenn keine Art ueber Threshold", results.isEmpty())
    }

    @Test
    fun `topK=1 liefert nur das erste resultat — backward kompat`() {
        val s = scores(0.7f, 0.6f, 0.5f)  // 3 Arten ueber Threshold
        val results = BirdNetV3Classifier.filterAndRank(s, LABELS, topK = 1, threshold = 0.3f)

        assertEquals("TopK=1 → genau 1 Resultat (Backward-Kompat)", 1, results.size)
        assertEquals("Das beste Resultat wird behalten", 0.7f, results[0].confidence, 0.001f)
        assertEquals("Species0 hat hoechste Konfidenz", "Species0", results[0].scientificName)
    }

    @Test
    fun `topK=5 mit nur 2 arten ueber threshold ergibt 2 nicht 5`() {
        val s = scores(0.8f, 0.7f, 0.2f, 0.1f)  // nur 2 ueber 0.3, TopK=5
        val results = BirdNetV3Classifier.filterAndRank(s, LABELS, topK = 5, threshold = 0.3f)

        assertEquals("Nur tatsaechliche Treffer, nicht aufgefuellt bis 5", 2, results.size)
        assertEquals("Species0 zuerst (0.8)", 0.8f, results[0].confidence, 0.001f)
        assertEquals("Species1 zweite (0.7)", 0.7f, results[1].confidence, 0.001f)
    }
}
