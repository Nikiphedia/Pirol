package ch.etasystems.pirol.ml

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T224: Regeln des Zweitmodells (Status, Fensterzuordnung, Fenster, Ueberlappung, Serialisierung). */
class SecondaryCheckTest {

    private fun cr(name: String, conf: Float) = AudioClassifier.ClassifierResult(name, name, conf, 0f, 3f)

    private val labels = setOf("turdus_merula", "parus_major", "erithacus_rubecula")

    private fun det(start: Float = 0f, best: Float? = null, status: String? = null) = DetectionResult(
        id = "d1", scientificName = "Turdus merula", commonName = "Amsel", confidence = 0.82f,
        timestampMs = 0L, chunkStartSec = start, chunkEndSec = start + 3f, sampleRate = 48000,
        bestStartSec = best, bestEndSec = best?.plus(3f), secondaryStatus = status
    )

    // ── Status ──

    @Test
    fun knownSpecies_isChecked_withItsScoreEvenBelowThreshold() {
        val o = SecondaryCheck.evaluate("Turdus merula", listOf(cr("Turdus_merula", 0.12f), cr("Parus_major", 0.05f)), labels, 0.5f)!!
        assertEquals(SecondaryCheck.STATUS_CHECKED, o.status)
        assertEquals(0.12f, o.confidence!!, 1e-6f)
        assertNull(o.topSpecies)
    }

    @Test
    fun unknownSpecies_isNotCheckable_neverDisagrees() {
        val o = SecondaryCheck.evaluate("Aquila chrysaetos", listOf(cr("Parus_major", 0.95f)), labels, 0.5f)!!
        assertEquals(SecondaryCheck.STATUS_NOT_CHECKABLE, o.status)
        assertNull(o.confidence)
        assertNull(o.topSpecies)
    }

    @Test
    fun otherTopSpeciesAboveThreshold_disagrees() {
        val o = SecondaryCheck.evaluate("Turdus merula", listOf(cr("Parus_major", 0.71f), cr("Turdus_merula", 0.2f)), labels, 0.5f)!!
        assertEquals(SecondaryCheck.STATUS_DISAGREES, o.status)
        assertEquals(0.2f, o.confidence!!, 1e-6f)
        assertEquals("Parus_major", o.topSpecies)
        assertEquals(0.71f, o.topConfidence!!, 1e-6f)
    }

    @Test
    fun otherTopSpeciesBelowThreshold_isChecked() {
        val o = SecondaryCheck.evaluate("Turdus merula", listOf(cr("Parus_major", 0.4f), cr("Turdus_merula", 0.2f)), labels, 0.5f)!!
        assertEquals(SecondaryCheck.STATUS_CHECKED, o.status)
    }

    @Test
    fun implausibleTopSpecies_skippedForDisagreement() {
        val o = SecondaryCheck.evaluate(
            "Turdus merula", listOf(cr("Parus_major", 0.9f), cr("Turdus_merula", 0.3f)), labels, 0.5f,
            isPlausible = { it != "Parus_major" }
        )!!
        assertEquals(SecondaryCheck.STATUS_CHECKED, o.status)
    }

    @Test
    fun speciesMissingFromScoresButInLabels_scoreZero() {
        val o = SecondaryCheck.evaluate("Erithacus rubecula", listOf(cr("Turdus_merula", 0.1f)), labels, 0.5f)!!
        assertEquals(SecondaryCheck.STATUS_CHECKED, o.status)
        assertEquals(0f, o.confidence!!, 0f)
    }

    @Test
    fun noResults_isNull() {
        assertNull(SecondaryCheck.evaluate("Turdus merula", emptyList(), labels, 0.5f))
    }

    // ── Fensterzuordnung ──

    @Test
    fun shouldApply_withoutResult_always() {
        assertTrue(SecondaryCheck.shouldApply(det(start = 0f, best = 6f), 3f))
    }

    @Test
    fun shouldApply_withResult_onlyForBestWindow() {
        val d = det(start = 0f, best = 6f, status = SecondaryCheck.STATUS_CHECKED)
        assertTrue(SecondaryCheck.shouldApply(d, 6f))
        assertFalse(SecondaryCheck.shouldApply(d, 3f))
        // ohne bestStartSec ist das erste Fenster das beste
        assertTrue(SecondaryCheck.shouldApply(det(start = 0f, status = SecondaryCheck.STATUS_CHECKED), 0f))
    }

    @Test
    fun apply_setsOnlySecondaryFields() {
        val o = SecondaryCheck.Outcome(SecondaryCheck.STATUS_DISAGREES, 0.2f, "Parus_major", 0.7f)
        val d = SecondaryCheck.apply(det(), "birdnet24_global6k", o)
        assertEquals("birdnet24_global6k", d.secondaryModelId)
        assertEquals(SecondaryCheck.STATUS_DISAGREES, d.secondaryStatus)
        assertEquals("Parus_major", d.secondaryTopSpecies)
        assertEquals(det(), d.copy(
            secondaryModelId = null, secondaryStatus = null, secondaryConfidence = null,
            secondaryTopSpecies = null, secondaryTopConfidence = null
        ))
    }

    // ── Fenster ──

    @Test
    fun fitWindow_sameLength_unchanged_shorter_centerCrop_longer_centerPad() {
        val pcm = ShortArray(10) { (it + 1).toShort() }
        assertTrue(SecondaryCheck.fitWindow(pcm, 10) === pcm)
        assertEquals((3..8).map { it.toShort() }, SecondaryCheck.fitWindow(pcm, 6).toList())
        val padded = SecondaryCheck.fitWindow(pcm, 14)
        assertEquals(14, padded.size)
        assertEquals(listOf<Short>(0, 0, 1), padded.take(3))
        assertEquals(listOf<Short>(10, 0, 0), padded.takeLast(3))
    }

    @Test
    fun windowSamplesAt_fiveSecondsAt48k() {
        assertEquals(240_000, SecondaryCheck.windowSamplesAt(5000, 48000))
        assertEquals(144_000, SecondaryCheck.windowSamplesAt(3000, 48000))
    }

    // ── Ueberlappung des Zweitmodells ──

    @Test
    fun overlapGate_zeroPercent_oneWindowPerWindowLengthPerSpecies() {
        val g = SecondaryCheck.OverlapGate(windowMs = 3000, overlapPercent = 0)
        assertTrue(g.accept("Turdus merula", 0f))
        assertFalse(g.accept("Turdus_merula", 1f))   // gleiche Art (Schluessel), < 3 s
        assertTrue(g.accept("Parus major", 1f))      // neue Art immer
        assertFalse(g.accept("Turdus merula", 2f))
        assertTrue(g.accept("Turdus merula", 3f))
        assertTrue(g.accept("Turdus merula", 0.5f))  // Zeit rueckwaerts (neue Aufnahme) → pruefen
    }

    @Test
    fun overlapGate_67Percent_everyPrimaryWindow() {
        val g = SecondaryCheck.OverlapGate(windowMs = 3000, overlapPercent = 67)
        assertTrue(g.accept("Turdus merula", 0f))
        assertTrue(g.accept("Turdus merula", 1f))
        assertTrue(g.accept("Turdus merula", 2f))
    }

    @Test
    fun modelLabel_stripsParenthesis_unknownIsId() {
        assertEquals("BirdNET 3.0 FP16", SecondaryCheck.modelLabel("v3_final_fp16"))
        assertEquals("custom_x", SecondaryCheck.modelLabel("custom_x"))
        assertNull(SecondaryCheck.modelLabel(null))
    }

    // ── Serialisierung (additiv) ──

    private val json = Json { prettyPrint = false }            // wie SessionManager
    private val jsonLenient = Json { ignoreUnknownKeys = true }

    @Test
    fun oldLine_withoutSecondaryFields_parsesToNull() {
        val old = """{"id":"a","scientificName":"Turdus merula","commonName":"Amsel","confidence":0.9,""" +
            """"timestampMs":1,"chunkStartSec":0.0,"chunkEndSec":3.0,"sampleRate":48000}"""
        val d = jsonLenient.decodeFromString<DetectionResult>(old)
        assertNull(d.secondaryModelId)
        assertNull(d.secondaryStatus)
        assertNull(d.secondaryConfidence)
        assertNull(d.secondaryTopSpecies)
        assertNull(d.secondaryTopConfidence)
    }

    @Test
    fun withoutSecondary_encodedLineHasNoNewKeys() {
        assertFalse(json.encodeToString(det()).contains("secondary"))
    }

    @Test
    fun withSecondary_roundTrip() {
        val d = SecondaryCheck.apply(det(), "birdnet24_global6k",
            SecondaryCheck.Outcome(SecondaryCheck.STATUS_DISAGREES, 0.2f, "Parus_major", 0.7f))
        val line = json.encodeToString(d)
        assertTrue(line.contains("\"secondaryStatus\":\"DISAGREES\""))
        assertEquals(d, jsonLenient.decodeFromString<DetectionResult>(line))
    }
}
