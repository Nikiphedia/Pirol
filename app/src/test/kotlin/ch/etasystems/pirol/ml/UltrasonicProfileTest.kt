package ch.etasystems.pirol.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** T293: Profil-Erweiterung Ultraschall (minSourceRate, taxonGroup, display, ignoreLabels) */
class UltrasonicProfileTest {

    private val sha = "a".repeat(64)

    private fun json(
        minSourceRate: String = "\"minSourceRate\": 250000,",
        extra: String = "\"taxonGroup\": \"bats\", \"display\": { \"freqRangeHz\": [9000, 150000], \"playbackTimeExpansion\": 10 },",
        scoresExtra: String = ", \"ignoreLabels\": [\"Background\"]"
    ) = """
        {
          "schema": "pirol-model/1", "id": "bat_test", "name": "Bat", "runtime": "onnx",
          "modelFile": "bat.onnx", "modelSha256": "$sha", "license": "CC BY 4.0",
          $extra
          "input": { "name": "audio", "sampleRate": 384000, "windowSec": 1.024666667, $minSourceRate "layout": "waveform" },
          "outputs": [ { "name": "scores", "role": "scores", "transform": "none", "labels": "species"$scoresExtra } ],
          "labels": { "species": { "file": "bat.csv", "sha256": "$sha", "scientificColumn": "scientific_name" } }
        }
    """.trimIndent()

    private fun expectSchemaError(text: String) {
        try {
            ModelProfileJson.parse(text)
            fail("Schemafehler erwartet")
        } catch (e: ModelProfileJson.ProfileError) {
            assertEquals(e.detail, ModelProfileJson.ErrorCode.SCHEMA, e.code)
        }
    }

    @Test fun `neue Felder werden gelesen`() {
        val p = ModelProfileJson.parse(json()).profile
        assertEquals(250000, p.minSourceRate)
        assertEquals("bats", p.taxonGroup)
        assertEquals(9000 to 150000, p.freqRangeHz)
        assertEquals(10, p.playbackTimeExpansion)
        assertEquals(setOf("background"), p.ignoreLabels)
        assertTrue(p.isUltrasonic)
        assertEquals(393472, p.windowSamples)
    }

    @Test fun `altes Profil ohne neue Felder bleibt gueltig`() {
        val p = ModelProfileJson.parse(json(minSourceRate = "", extra = "", scoresExtra = "")).profile
        assertNull(p.minSourceRate)
        assertNull(p.taxonGroup)
        assertNull(p.freqRangeHz)
        assertNull(p.playbackTimeExpansion)
        assertTrue(p.ignoreLabels.isEmpty())
        assertFalse(p.isUltrasonic)
        assertEquals(1, p.effectiveTimeExpansion)
        assertTrue(p.supportsSourceRate(8000))
    }

    @Test fun `Zeitdehnung Default 10 bei Ultraschall`() {
        val p = ModelProfileJson.parse(json(extra = "\"taxonGroup\": \"bats\",")).profile
        assertEquals(10, p.effectiveTimeExpansion)
    }

    @Test fun `ungueltige neue Felder sind Schemafehler`() {
        expectSchemaError(json(minSourceRate = "\"minSourceRate\": 0,"))
        expectSchemaError(json(extra = "\"taxonGroup\": \"Fleder Maus\","))
        expectSchemaError(json(extra = "\"display\": { \"freqRangeHz\": [9000] },"))
        expectSchemaError(json(extra = "\"display\": { \"freqRangeHz\": [150000, 9000] },"))
        expectSchemaError(json(extra = "\"display\": { \"playbackTimeExpansion\": 0 },"))
        expectSchemaError(json(scoresExtra = ", \"ignoreLabels\": [\"\"]"))
    }

    @Test fun `minSourceRate Sperre`() {
        assertFalse(ModelProfile.sourceRateSupported(250000, 48000))
        assertFalse(ModelProfile.sourceRateSupported(250000, 249999))
        assertTrue(ModelProfile.sourceRateSupported(250000, 250000))
        assertTrue(ModelProfile.sourceRateSupported(250000, 384000))
        assertTrue(ModelProfile.sourceRateSupported(null, 8000))
        val p = ModelProfileJson.parse(json()).profile
        assertFalse(p.supportsSourceRate(48000))
        assertTrue(p.supportsSourceRate(384000))
    }

    @Test fun `ignoreLabels melden Background nie`() {
        val labels = listOf("Pipistrellus_pipistrellus" to "Zwergfledermaus", "Background" to "Hintergrund")
        val scores = floatArrayOf(0.6f, 0.9f)
        val plain = BirdNetV3Classifier.filterAndRank(scores, labels, topK = 5, threshold = 0.1f)
        assertEquals(listOf("Background", "Pipistrellus_pipistrellus"), plain.map { it.scientificName })
        val p = ModelProfileJson.parse(json()).profile
        val ranked = BirdNetV3Classifier.filterAndRank(scores, labels, 5, 0.1f, 1.0f, p.ignoreLabels)
        assertEquals(listOf("Pipistrellus_pipistrellus"), ranked.map { it.scientificName })
        assertTrue(p.isIgnoredLabel("background"))
        assertTrue(p.isIgnoredLabel(" Background "))
        assertFalse(p.isIgnoredLabel("Pipistrellus_pipistrellus"))
    }

    @Test fun `Ultraschall-Zweitmodell nur zu Ultraschall-Primaer`() {
        val bat = ModelProfileJson.parse(json()).profile
        val bird = ModelProfile.V3_FINAL_FP16
        assertFalse(ModelRoles.secondaryAllowed(bird, bat))
        assertFalse(ModelRoles.secondaryAllowed(null, bat))
        assertTrue(ModelRoles.secondaryAllowed(bat, bat.copy(id = "bat2")))
        assertTrue(ModelRoles.secondaryAllowed(bat, bird))
        assertTrue(ModelRoles.secondaryAllowed(bird, ModelProfile.PREVIEW31_FP16))
        assertTrue(ModelRoles.secondaryAllowed(bird, null))
        assertFalse(ModelTableRow.secondaryEnabled(true, false, rateAllowed = false))
        assertTrue(ModelTableRow.secondaryEnabled(true, false))
    }

    @Test fun `Testpaket aus dem Spike wird gelesen (nur wenn vorhanden)`() {
        val f = File("D:/S/pirol-bat-spike/out/pkg_fp16/bsgbat_v021_r1_fp16.pirol.json")
        assumeTrue(f.exists())
        val p = ModelProfileJson.parse(f.readText(Charsets.UTF_8)).profile
        assertEquals(384000, p.sampleRate)
        assertEquals(393472, p.windowSamples)
    }
}
