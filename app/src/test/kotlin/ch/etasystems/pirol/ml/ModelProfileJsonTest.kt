package ch.etasystems.pirol.ml

import ch.etasystems.pirol.audio.AudioChunk
import ch.etasystems.pirol.audio.dsp.AudioResampler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * T151a: Profil-JSON pirol-model/1 (Parser, Pflichtfelder, I/O-Pruefung), generische Label-CSV,
 * Fenster/Hop fuer andere Abtastraten und Fensterlaengen, Score-Umrechnung.
 */
class ModelProfileJsonTest {

    private val sha = "a".repeat(64)
    private val sha2 = "B".repeat(64)

    /** Beispiel aus dem Vertrag (Nighthawk), optional mit ersetzten Teilen */
    private fun json(
        schema: String = "pirol-model/1",
        id: String = "nighthawk_v1",
        runtime: String = "onnx",
        sampleRate: Int = 22050,
        windowSec: Double = 1.0,
        layout: String = "waveform",
        outputs: String = """
            { "name": "species", "role": "scores", "transform": "sigmoid", "labels": "species" },
            { "name": "family", "role": "aux", "transform": "sigmoid", "labels": "family" },
            { "name": "embedding", "role": "embedding" }
        """,
        extra: String = ""
    ) = """
        {
          "schema": "$schema",
          "id": "$id",
          "name": "Nighthawk 1.0",
          "family": "nighthawk",
          "runtime": "$runtime",
          "modelFile": "nighthawk_v1.onnx",
          "modelSha256": "$sha",
          "license": "MIT",
          $extra
          "input": { "name": "inputs", "sampleRate": $sampleRate, "windowSec": $windowSec, "layout": "$layout" },
          "outputs": [ $outputs ],
          "labels": {
            "species": { "file": "nighthawk_v1_species.csv", "sha256": "$sha", "delimiter": ",", "header": true,
                         "scientificColumn": "scientific_name", "commonColumn": "common_name" },
            "family":  { "file": "nighthawk_v1_family.csv", "sha256": "$sha2", "delimiter": "\t", "header": false,
                         "scientificColumn": 0 }
          }
        }
    """.trimIndent()

    private fun expectError(code: ModelProfileJson.ErrorCode, text: String) {
        try {
            ModelProfileJson.parse(text)
            fail("Fehler $code erwartet")
        } catch (e: ModelProfileJson.ProfileError) {
            assertEquals(e.detail, code, e.code)
        }
    }

    // --- Parser: gueltig ---

    @Test
    fun `gueltiges Profil wird zu ModelProfile`() {
        val parsed = ModelProfileJson.parse(json(extra = "\"unbekanntesFeld\": 42,"))
        val p = parsed.profile
        assertEquals("nighthawk_v1", p.id)
        assertEquals("nighthawk", p.family)
        assertEquals(ModelProfile.Source.IMPORTED, p.source)
        assertEquals("onnx", p.runtime)
        assertEquals("inputs", p.inputName)
        assertEquals(22050, p.sampleRate)
        assertEquals(22050, p.windowSamples)
        assertEquals(1000, p.windowMs)
        assertEquals("species", p.scoreOutputName)
        assertEquals("embedding", p.embeddingOutputName)
        assertTrue(p.hasEmbedding)
        assertEquals(listOf("family"), p.auxOutputNames)
        assertEquals(ModelProfile.ScoreTransform.SIGMOID, p.scoreTransform)
        assertEquals(ModelProfile.LabelFormat.GENERIC_CSV, p.labelFormat)
        assertEquals("nighthawk_v1_species.csv", p.labelFileName)
        assertEquals("sha256:$sha", p.labelChecksum)
        assertEquals("sha256:$sha", p.checksum)
        assertEquals("embeddings_nighthawk_v1.bsed", p.embeddingDbFileName)
        assertEquals("MIT", p.licenseNote)
        assertNull(p.sizeBytes)
        assertEquals(',', p.genericLabels!!.delimiter)
        assertEquals("scientific_name", p.genericLabels!!.scientificColumn.name)
        assertEquals(setOf("species", "family"), parsed.usedLabels.keys)
        // Tab-Delimiter und Index-Spalte
        val fam = ModelProfileJson.genericLabels(parsed.labels.getValue("family"))
        assertEquals('\t', fam.delimiter)
        assertEquals(0, fam.scientificColumn.index)
        assertFalse(fam.header)
    }

    @Test
    fun `ohne Embedding-Ausgang keine Aehnlichkeitssuche`() {
        val p = ModelProfileJson.parse(json(outputs = """
            { "name": "species", "role": "scores", "labels": "species" }
        """)).profile
        assertFalse(p.hasEmbedding)
        assertEquals(ModelProfile.ScoreTransform.NONE, p.scoreTransform)  // transform fehlt = none
        assertTrue(p.auxOutputNames.isEmpty())
    }

    @Test
    fun `Katalogprofile haben weiterhin Embedding und 3 s`() {
        for (p in ModelProfile.CATALOG) {
            assertTrue(p.id, p.hasEmbedding)
            assertEquals(3000, p.windowMs)
            assertEquals(ModelProfile.Source.CATALOG, p.source)
        }
    }

    // --- Parser: ungueltig ---

    @Test
    fun `falsches Schema`() = expectError(ModelProfileJson.ErrorCode.SCHEMA, json(schema = "pirol-model/2"))

    @Test
    fun `kaputtes JSON`() = expectError(ModelProfileJson.ErrorCode.SCHEMA, "{ \"schema\": ")

    @Test
    fun `runtime tflite wird abgelehnt`() = expectError(ModelProfileJson.ErrorCode.RUNTIME, json(runtime = "tflite"))

    @Test
    fun `id mit Grossbuchstaben`() = expectError(ModelProfileJson.ErrorCode.SCHEMA, json(id = "Nighthawk"))

    @Test
    fun `layout spectrogram wird abgelehnt`() = expectError(ModelProfileJson.ErrorCode.SCHEMA, json(layout = "spectrogram"))

    @Test
    fun `Abtastrate und Fenster muessen positiv sein`() {
        expectError(ModelProfileJson.ErrorCode.SCHEMA, json(sampleRate = 0))
        expectError(ModelProfileJson.ErrorCode.SCHEMA, json(windowSec = 0.0))
    }

    @Test
    fun `genau ein scores-Ausgang`() {
        expectError(ModelProfileJson.ErrorCode.SCHEMA, json(outputs = """{ "name": "e", "role": "embedding" }"""))
        expectError(ModelProfileJson.ErrorCode.SCHEMA, json(outputs = """
            { "name": "a", "role": "scores", "labels": "species" },
            { "name": "b", "role": "scores", "labels": "species" }
        """))
    }

    @Test
    fun `unbekannte Labels und Transform`() {
        expectError(ModelProfileJson.ErrorCode.SCHEMA, json(outputs = """{ "name": "a", "role": "scores", "labels": "xyz" }"""))
        expectError(ModelProfileJson.ErrorCode.SCHEMA,
            json(outputs = """{ "name": "a", "role": "scores", "transform": "relu", "labels": "species" }"""))
    }

    @Test
    fun `Pflichtfeld license fehlt`() =
        expectError(ModelProfileJson.ErrorCode.SCHEMA, json().replace("\"license\": \"MIT\",", ""))

    @Test
    fun `modelFile mit Pfad wird abgelehnt`() =
        expectError(ModelProfileJson.ErrorCode.SCHEMA, json().replace("\"nighthawk_v1.onnx\"", "\"../x.onnx\""))

    @Test
    fun `kein SHA-256`() =
        expectError(ModelProfileJson.ErrorCode.SCHEMA, json().replace("\"modelSha256\": \"$sha\"", "\"modelSha256\": \"abc\""))

    // --- I/O-Pruefung (ohne ORT) ---

    private val parsed by lazy { ModelProfileJson.parse(json()) }
    private val okOutputs = mapOf("species" to 3, "family" to 2, "embedding" to 128)
    private val okLabels = mapOf("species" to 3, "family" to 2)

    @Test
    fun `IO passt bei 1-N und N und dynamisch`() {
        ModelProfileJson.checkIo(parsed, mapOf("inputs" to longArrayOf(1, 22050)), okOutputs, okLabels)
        ModelProfileJson.checkIo(parsed, mapOf("inputs" to longArrayOf(22050)), okOutputs, okLabels)
        ModelProfileJson.checkIo(parsed, mapOf("inputs" to longArrayOf(-1, -1)), okOutputs, okLabels)
    }

    private fun expectIo(inputs: Map<String, LongArray>, outputs: Map<String, Int>, labels: Map<String, Int>) {
        try {
            ModelProfileJson.checkIo(parsed, inputs, outputs, labels)
            fail("IO_MISMATCH erwartet")
        } catch (e: ModelProfileJson.ProfileError) {
            assertEquals(ModelProfileJson.ErrorCode.IO_MISMATCH, e.code)
        }
    }

    @Test
    fun `IO Fehler werden erkannt`() {
        val inOk = mapOf("inputs" to longArrayOf(1, 22050))
        expectIo(mapOf("audio" to longArrayOf(1, 22050)), okOutputs, okLabels)          // Eingangsname
        expectIo(mapOf("inputs" to longArrayOf(1, 96000)), okOutputs, okLabels)         // falsche Laenge
        expectIo(mapOf("inputs" to longArrayOf(1, 128, 22050)), okOutputs, okLabels)    // Spektrogramm-Form
        expectIo(inOk, okOutputs - "family", okLabels)                                   // aux-Ausgang fehlt
        expectIo(inOk, okOutputs + ("species" to 4), okLabels)                           // Labelanzahl
        expectIo(inOk, okOutputs + ("family" to 3), okLabels)                            // aux-Labelanzahl
    }

    // --- Label-Parser generisch ---

    @Test
    fun `generische CSV mit Kopfzeile, Anfuehrungszeichen und BOM`() {
        val text = "﻿id,scientific_name,common_name\n" +
            "0,Turdus_merula,\"Blackbird, Eurasian\"\n\n" +
            "1,Chordeiles minor,\n"
        val spec = ModelProfile.GenericLabels(',', true, ModelProfile.Column(name = "scientific_name"),
            ModelProfile.Column(name = "common_name"))
        val labels = ModelLabelParser.parse(text, ModelProfile.LabelFormat.GENERIC_CSV, generic = spec)
        assertEquals(2, labels.size)
        assertEquals("Turdus merula", labels[0].scientificName)  // Unterstrich → Leerzeichen
        assertEquals("Blackbird, Eurasian", labels[0].commonName)
        assertEquals("Chordeiles minor", labels[1].commonName)   // leer → wissenschaftlich
    }

    @Test
    fun `generische CSV ohne Kopfzeile mit Tab und Index`() {
        val text = "Passeriformes\tSperlingsvoegel\nStrigiformes\tEulen\n"
        val spec = ModelProfile.GenericLabels('\t', false, ModelProfile.Column(index = 0), ModelProfile.Column(index = 1))
        val labels = ModelLabelParser.parseGeneric(text, spec)
        assertEquals(listOf("Passeriformes", "Strigiformes"), labels.map { it.scientificName })
        assertEquals("Eulen", labels[1].commonName)
    }

    @Test
    fun `generische CSV Semikolon ohne Trivialnamen`() {
        val labels = ModelLabelParser.parseGeneric("sci\nParus major\n",
            ModelProfile.GenericLabels(';', true, ModelProfile.Column(name = "sci")))
        assertEquals("Parus major", labels.single().commonName)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `fehlende Spalte ist ein Fehler`() {
        ModelLabelParser.parseGeneric("a,b\nx,y\n", ModelProfile.GenericLabels(',', true, ModelProfile.Column(name = "sci")))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `leerer wissenschaftlicher Name ist ein Fehler`() {
        ModelLabelParser.parseGeneric("sci,com\n,Amsel\n", ModelProfile.GenericLabels(',', true, ModelProfile.Column(name = "sci")))
    }

    // --- Fenster und Hop ---

    @Test
    fun `Fensterlaenge in Samples und ms`() {
        assertEquals(22050, ModelProfileJson.windowSamples(22050, 1.0))
        assertEquals(160000, ModelProfileJson.windowSamples(32000, 5.0))
        assertEquals(144000, ModelProfileJson.windowSamples(48000, 3.0))
        val p5 = ModelProfileJson.parse(json(sampleRate = 32000, windowSec = 5.0)).profile
        assertEquals(5000, p5.windowMs)
        assertEquals(5f, p5.windowSec, 1e-6f)
    }

    @Test
    fun `Hop bezieht sich auf die Fensterlaenge`() {
        assertEquals(listOf(1000, 500, 333), listOf(0, 50, 67).map { OverlapFallback.hopMsFor(it, 1000) })
        assertEquals(listOf(5000, 2500, 1667), listOf(0, 50, 67).map { OverlapFallback.hopMsFor(it, 5000) })
        assertEquals(listOf(3000, 1500, 1000), listOf(0, 50, 67).map { OverlapFallback.hopMsFor(it, 3000) })
    }

    /** Analyse-Fenster am 48-kHz-Strom: Laenge = Fenster, Starts im Hop-Raster → chunkStart/End stimmen */
    private fun checkWindows(windowMs: Int, overlap: Int) {
        val sr = 48000
        val hopMs = OverlapFallback.hopMsFor(overlap, windowMs)
        val acc = ChunkAccumulator(windowDurationMs = windowMs, hopMs = hopMs)
        val total = sr * 12
        val windows = mutableListOf<AnalysisWindow>()
        var pos = 0
        while (pos < total) {
            val n = minOf(4800, total - pos)
            windows += acc.feed(AudioChunk(ShortArray(n), sr, 0L)).windows
            pos += n
        }
        val winSamples = sr.toLong() * windowMs / 1000
        val hopSamples = sr.toLong() * hopMs / 1000
        assertEquals(((total - winSamples) / hopSamples + 1).toInt(), windows.size)
        windows.forEachIndexed { k, w ->
            assertEquals(k * hopSamples, w.startIndex)
            assertEquals(winSamples.toInt(), w.pcm.size)
            // chunkEndSec - chunkStartSec (InferenceWorker.mapResults) = Fensterlaenge
            assertEquals(windowMs / 1000f, w.pcm.size.toFloat() / w.sampleRate, 1e-4f)
        }
    }

    @Test
    fun `Fenster 22050 Hz 1 s`() {
        listOf(0, 50, 67).forEach { checkWindows(1000, it) }
        // 48 kHz-Fenster von 1 s → 22050 Samples Modell-Eingang
        assertEquals(22050, AudioResampler.resample(ShortArray(48000), 48000, 22050, AudioResampler.Mode.POLYPHASE).size)
    }

    @Test
    fun `Fenster 32000 Hz 5 s`() {
        listOf(0, 50, 67).forEach { checkWindows(5000, it) }
        assertEquals(160000, AudioResampler.resample(ShortArray(240000), 48000, 32000, AudioResampler.Mode.POLYPHASE).size)
    }

    @Test
    fun `Fenster 48000 Hz 3 s ohne Resampling`() {
        listOf(0, 50, 67).forEach { checkWindows(3000, it) }
        val pcm = ShortArray(144000) { (it % 100).toShort() }
        val out = AudioResampler.resample(pcm, 48000, 48000, AudioResampler.Mode.POLYPHASE)
        assertEquals(144000, out.size)
        assertEquals(pcm[99] / 32768f, out[99], 1e-6f)
    }

    // --- Score-Umrechnung ---

    @Test
    fun `Score-Transform sigmoid softmax none`() {
        val sig = BirdNetV3Classifier.applyTransform(floatArrayOf(-2f, 0f, 2f), ModelProfile.ScoreTransform.SIGMOID)
        assertEquals(0.1192f, sig[0], 1e-4f)
        assertEquals(0.5f, sig[1], 1e-6f)
        assertEquals(0.8808f, sig[2], 1e-4f)
        val soft = BirdNetV3Classifier.applyTransform(floatArrayOf(1f, 2f, 3f), ModelProfile.ScoreTransform.SOFTMAX)
        assertEquals(1f, soft.sum(), 1e-5f)
        assertEquals(0.6652f, soft[2], 1e-4f)
        val none = BirdNetV3Classifier.applyTransform(floatArrayOf(0.3f), ModelProfile.ScoreTransform.NONE)
        assertEquals(0.3f, none[0], 0f)
    }
}
