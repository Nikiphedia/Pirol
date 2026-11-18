package ch.etasystems.pirol.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T103a: Label-Parser (Preview-Semikolon-CSV, 3.0-final-Komma-CSV), Profil-Zuordnung und Ausgangswahl.
 */
class ModelProfileTest {

    // --- Parser Preview ---

    @Test
    fun `preview csv mit BOM wird gelesen`() {
        val text = "﻿idx;id;sci_name;com_name;class;order\n" +
            "0;3;Abeillia abeillei;Emerald-chinned Hummingbird;Aves;Apodiformes\n" +
            "1;5;Turdus merula;Eurasian Blackbird;Aves;Passeriformes\n\n"
        val labels = ModelLabelParser.parse(text, ModelProfile.LabelFormat.PREVIEW_SEMICOLON)
        assertEquals(2, labels.size)
        assertEquals("Turdus merula", labels[1].scientificName)
        assertEquals("Eurasian Blackbird", labels[1].commonName)
        assertTrue(labels[1].namesByLanguage.isEmpty())
    }

    @Test
    fun `preview asset hat 11560 labels`() {
        val file = File("src/main/assets/models/birdnet_v3_labels.csv")
        if (!file.exists()) return  // Asset nur im Modul-Verzeichnis vorhanden
        val labels = ModelLabelParser.parsePreview(file.readText())
        assertEquals(ModelProfile.PREVIEW31_FP16.speciesCount, labels.size)
    }

    // --- Parser 3.0 final ---

    private val finalCsv = "ix,birdnet_id,scientific_name,common_name,taxon_group,inat_id,ebird_code," +
        "common_name_en,common_name_de,common_name_he,common_name_pt_PT\n" +
        "1,BN10484,Turdus merula,Common Blackbird,Aves,12716,eurbla,Common Blackbird,Amsel,,Melro-preto\n" +
        "0,BN00975,Astur gentilis,Eurasian Goshawk,Aves,5106,norgos,Eurasian Goshawk,Habicht,\"בבלר בליית\"\"ס\",\n" +
        "2,BN99999,Siren,Siren,Anthropogenic,,,Siren,\"Sirene, Alarm\",,\n"

    @Test
    fun `final csv wird nach ix sortiert und mit sprachspalten gelesen`() {
        val labels = ModelLabelParser.parse(finalCsv, ModelProfile.LabelFormat.FINAL_CSV)
        assertEquals(3, labels.size)
        assertEquals("Astur gentilis", labels[0].scientificName)
        assertEquals("Habicht", labels[0].namesByLanguage["de"])
        assertEquals("Melro-preto", labels[1].namesByLanguage["pt_PT"])
        assertEquals("Common Blackbird", labels[1].commonName)
    }

    @Test
    fun `final csv behandelt anfuehrungszeichen und kommas im feld`() {
        val labels = ModelLabelParser.parseFinal(finalCsv)
        assertEquals("בבלר בליית\"ס", labels[0].namesByLanguage["he"])
        assertEquals("Sirene, Alarm", labels[2].namesByLanguage["de"])
        assertNull(labels[1].namesByLanguage["he"])  // leere Felder nicht uebernehmen
    }

    @Test
    fun `final csv sprachfilter behaelt nur gewuenschte sprachen`() {
        val labels = ModelLabelParser.parseFinal(finalCsv, setOf("de", "en"))
        assertEquals(setOf("de", "en"), labels[1].namesByLanguage.keys)
    }

    // --- Profil-Zuordnung ---

    @Test
    fun `katalog dateiname hat vorrang`() {
        assertEquals(ModelProfile.V3_FINAL_FP16, ModelProfile.resolve("birdnet_v3_final_fp16.onnx", 1L))
        assertEquals(ModelProfile.PREVIEW31_FP16, ModelProfile.resolve("birdnet_v3_fp16.onnx", 1L))
    }

    @Test
    fun `importierte originaldatei wird ueber die groesse erkannt`() {
        assertEquals(
            ModelProfile.V3_FINAL_FP16_PRUNED,
            ModelProfile.resolve("BirdNET+_V3.0_Global_12K_FP16_pruned.onnx", 71_939_197L)
        )
    }

    @Test
    fun `unbekannte alte datei bleibt beim preview profil`() {
        val p = ModelProfile.resolve("birdnet_v3.onnx", 516_000_000L)
        assertEquals(ModelProfile.LEGACY_DEFAULT, p)
        assertEquals("embeddings.bsed", p.embeddingDbFileName)
    }

    @Test
    fun `katalog ist eindeutig und standard ist 3_0 final fp16`() {
        val c = ModelProfile.CATALOG
        assertEquals(c.size, c.map { it.id }.toSet().size)
        assertEquals(c.size, c.map { it.fileName }.toSet().size)
        assertEquals(c.size, c.mapNotNull { it.sizeBytes }.toSet().size)
        assertEquals(ModelProfile.V3_FINAL_FP16, c.first())
        // Embedding-DB der finalen Modelle getrennt vom Preview
        assertTrue(c.filter { it.family == "birdnet3" }.none { it.embeddingDbFileName == "embeddings.bsed" })
    }

    // --- Ausgangswahl / Score-Umrechnung ---

    @Test
    fun `ausgang wird ueber namen gewaehlt nicht ueber index`() {
        val names = listOf("predictions", "embeddings_out")
        assertEquals(0, BirdNetV3Classifier.selectOutputIndex(names, "predictions", 1))
        assertEquals(1, BirdNetV3Classifier.selectOutputIndex(names, "embeddings_out", 0))
        // Unbekannter Name → Index laut Profil
        assertEquals(1, BirdNetV3Classifier.selectOutputIndex(listOf("a", "b"), "predictions", 1))
        assertEquals(0, BirdNetV3Classifier.selectOutputIndex(listOf("a"), null, 1))
    }

    @Test
    fun `score umrechnung sigmoid und softmax`() {
        val sig = BirdNetV3Classifier.applyTransform(floatArrayOf(0f), ModelProfile.ScoreTransform.SIGMOID)
        assertEquals(0.5f, sig[0], 1e-6f)
        val soft = BirdNetV3Classifier.applyTransform(floatArrayOf(1f, 1f), ModelProfile.ScoreTransform.SOFTMAX)
        assertEquals(0.5f, soft[0], 1e-6f)
        val none = BirdNetV3Classifier.applyTransform(floatArrayOf(0.7f), ModelProfile.ScoreTransform.NONE)
        assertEquals(0.7f, none[0], 0f)
    }
}
