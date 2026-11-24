package ch.etasystems.pirol.ml

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** T261: Mensch-Labels pro Modellprofil (Name, nicht Index) und Score. */
class HumanLabelsTest {

    @Test
    fun preview_asset_hat_zwei_mensch_klassen() {
        val f = File("src/main/assets/models/birdnet_v3_labels.csv")
        val names = f.readLines().mapNotNull { it.split(';').getOrNull(2) }
        val idx = HumanLabels.indicesOf(names)
        assertEquals(listOf("Homo sapiens", "Homo Sapiens"), idx.map { names[it] })
    }

    @Test
    fun final_12k_homo_sapiens() {
        // Auszug 3.0 final (BN22686 Human); Nachbarn mit "homo" im Gattungsnamen zaehlen nicht
        val names = listOf("Hydrobates homochroa", "Homo sapiens", "Homoeoxipha lycoides", "Catamenia homochroa")
        assertArrayEquals(intArrayOf(1), HumanLabels.indicesOf(names))
    }

    @Test
    fun birdnet24_stimme_und_pfeifen_nicht_schritte() {
        val names = listOf("Dog", "Engine", "Human non-vocal", "Human vocal", "Human whistle", "Noise")
        assertArrayEquals(intArrayOf(3, 4), HumanLabels.indicesOf(names))
    }

    @Test
    fun perch_fsd50k_stimmklassen() {
        val names = listOf("Homo sapiens", "Speech", "Male_speech_and_man_speaking", "Conversation", "Laughter",
            "Cough", "Walk_and_footsteps", "Strix aluco", "Speech_synthesizer", "Crying_and_sobbing")
        val idx = HumanLabels.indicesOf(names).map { names[it] }
        assertEquals(listOf("Homo sapiens", "Speech", "Male_speech_and_man_speaking", "Conversation", "Laughter",
            "Speech_synthesizer", "Crying_and_sobbing"), idx)
        assertFalse(HumanLabels.isHuman("Cough"))
        assertTrue(HumanLabels.isHuman("  HUMAN_VOICE "))
    }

    @Test
    fun score_summe_gekappt_nan_ist_null() {
        val scores = floatArrayOf(0.9f, 0.3f, Float.NaN, 0.8f)
        assertEquals(1f, HumanLabels.score(scores, intArrayOf(0, 1, 3))!!, 0f)
        assertEquals(0.3f, HumanLabels.score(scores, intArrayOf(1, 2))!!, 1e-6f)
        assertNull(HumanLabels.score(scores, IntArray(0)))
        assertEquals(0f, HumanLabels.score(scores, intArrayOf(99))!!, 0f)
    }
}
