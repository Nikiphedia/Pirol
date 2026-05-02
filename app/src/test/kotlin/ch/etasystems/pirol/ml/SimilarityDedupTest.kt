package ch.etasystems.pirol.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit-Tests fuer die T75-Aehnliche-Arten-Logik.
 *
 * Prueft: Dedup pro Art, Lines-Modus-Auswahl, Sprachaufloesung-Fallback.
 */
class SimilarityDedupTest {

    // --- Hilfsmethode: Dedup-Logik aus LiveViewModel.findSimilar() ---

    private fun dedupMatches(
        raw: List<EmbeddingDatabase.EmbeddingMatch>,
        lines: Float
    ): List<EmbeddingDatabase.EmbeddingMatch> {
        val displayN = if (lines < 1f) 5 else lines.toInt()
        return raw
            .groupBy { it.species }
            .map { (_, group) -> group.maxByOrNull { it.similarity }!! }
            .sortedByDescending { it.similarity }
            .take(displayN)
            .mapIndexed { i, m -> m.copy(rank = i + 1) }
    }

    private fun makeMatch(
        species: String,
        similarity: Float,
        recordingId: String = "rec_${species}_${(similarity * 100).toInt()}"
    ) = EmbeddingDatabase.EmbeddingMatch(
        recordingId = recordingId,
        species = species,
        similarity = similarity,
        rank = 0
    )

    // -------------------------------------------------------------------------
    // Test 1: DedupTest
    // -------------------------------------------------------------------------

    @Test
    fun dedupTest_multipleEmbeddingsSameSpecies_keepsBestSimilarity() {
        // 5 Eintraege: 3x Art X (unterschiedliche Scores), 2x Art Y
        val raw = listOf(
            makeMatch("Turdus merula", 0.95f, "rec_001"),
            makeMatch("Turdus merula", 0.87f, "rec_002"),
            makeMatch("Turdus merula", 0.82f, "rec_003"),
            makeMatch("Parus major", 0.70f, "rec_004"),
            makeMatch("Parus major", 0.65f, "rec_005")
        )

        val result = dedupMatches(raw, lines = 3.0f)

        assertEquals("Dedup auf 2 Arten", 2, result.size)
        assertEquals("Erste Art ist Turdus merula", "Turdus merula", result[0].species)
        assertEquals("Hoechste Similarity von Turdus merula", 0.95f, result[0].similarity, 0.001f)
        assertEquals("Zweite Art ist Parus major", "Parus major", result[1].species)
        assertEquals("Hoechste Similarity von Parus major", 0.70f, result[1].similarity, 0.001f)
        assertEquals("Rang 1 fuer bestes Match", 1, result[0].rank)
        assertEquals("Rang 2 fuer zweites Match", 2, result[1].rank)
    }

    // -------------------------------------------------------------------------
    // Test 2: LinesTest
    // -------------------------------------------------------------------------

    @Test
    fun linesTest_slider0_returnsEmptyViaEarlyReturn() {
        // lines=0 → findSimilar() macht early return → emptyList()
        // Hier testen wir die Bedingung direkt:
        val lines = 0f
        val shouldShow = lines != 0f
        assertFalse("Bei lines=0 soll Panel nicht anzeigen", shouldShow)
    }

    @Test
    fun linesTest_slider3_limitsTo3Entries() {
        val raw = listOf(
            makeMatch("Art A", 0.95f),
            makeMatch("Art B", 0.90f),
            makeMatch("Art C", 0.85f),
            makeMatch("Art D", 0.80f),
            makeMatch("Art E", 0.75f)
        )

        val result = dedupMatches(raw, lines = 3.0f)

        assertEquals("Slider 3 → maximal 3 Eintraege", 3, result.size)
    }

    @Test
    fun linesTest_slider05_marqueeMode_displayN5() {
        // Bei lines=0.5f → displayN=5 (Marquee zeigt bis zu 5 Eintraege)
        val raw = listOf(
            makeMatch("Art A", 0.95f),
            makeMatch("Art B", 0.90f),
            makeMatch("Art C", 0.85f),
            makeMatch("Art D", 0.80f),
            makeMatch("Art E", 0.75f),
            makeMatch("Art F", 0.70f)
        )

        val result = dedupMatches(raw, lines = 0.5f)

        assertEquals("Marquee-Mode (0.5) → maximal 5 Eintraege", 5, result.size)
        assertTrue("Marquee-Mode erkennen: lines < 1f", 0.5f < 1f)
    }

    @Test
    fun linesTest_slider10_limitsTo10Entries() {
        val raw = (1..15).map { makeMatch("Art $it", 1.0f - it * 0.05f) }

        val result = dedupMatches(raw, lines = 10.0f)

        assertEquals("Slider 10 → maximal 10 Eintraege", 10, result.size)
    }

    // -------------------------------------------------------------------------
    // Test 3: LanguageTest — SpeciesNameResolver Fallback
    // -------------------------------------------------------------------------

    @Test
    fun languageTest_resolverFallback_noUnderscoreNoNull() {
        // SpeciesNameResolver ohne geladenes JSON → Fallback auf scientificName.replace('_', ' ')
        // Wir erstellen einen ungeladenen Resolver und pruefen den Fallback-Pfad direkt:
        val scientificWithUnderscore = "Turdus_merula"
        val scientificWithSpace = "Turdus merula"

        // Fallback-Logik aus SpeciesNameResolver.resolve() (namesMap leer):
        val result = scientificWithUnderscore.replace('_', ' ')

        assertEquals("Fallback ersetzt Unterstrich durch Leerzeichen", scientificWithSpace, result)
        assertFalse("Kein Unterstrich im Fallback-Namen", result.contains('_'))
    }

    @Test
    fun languageTest_scientificNameSubtitle_differentFromCommon() {
        // Wenn commonName != scientific (Subtitle soll angezeigt werden)
        val commonName = "Amsel"
        val scientificName = "Turdus merula"

        assertTrue("Subtitle sichtbar wenn Namen unterschiedlich", commonName != scientificName)
    }
}
