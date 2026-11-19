package ch.etasystems.pirol.ml

/**
 * T103a: Beschreibung eines Klassifikationsmodells (Datei, Eingang, Ausgang, Labels, Lizenz).
 *
 * Der Katalog [CATALOG] ersetzt die fruehere Liste `ModelManager.AVAILABLE_MODELS`.
 * Die I/O-Angaben der 3.0-final-Profile sind am 2026-11-18 mit onnxruntime 1.24 an der
 * FP16_pruned- und FP16-Datei geprueft (siehe Handover_T103a.md).
 *
 * @param checksum Pruefsumme der Modelldatei als "sha256:<hex>" oder "md5:<hex>" (null = nur Groesse pruefen)
 * @param sizeBytes exakte Dateigroesse laut Quelle; dient beim Import zur Profil-Erkennung
 * @param labelAssetPath Label-Datei in assets/ (Preview), sonst null
 * @param labelUrl Download-Adresse der Label-Datei (3.0 final), sonst null
 * @param labelFileName Dateiname der Label-Datei in filesDir/models/ (bei [labelUrl])
 * @param scoreOutputName Name des Score-Ausgangs (bevorzugt); [scoreOutputIndex] als Rueckfall
 * @param embeddingOutputName Name des Embedding-Ausgangs (bevorzugt); [embeddingOutputIndex] als Rueckfall
 * @param embeddingDbFileName Datei unter filesDir/embeddings/ — Vektoren verschiedener Modelle nie mischen
 * @param inputName T151a: Eingangsname im ONNX-Graph (null = erster Eingang)
 * @param genericLabels T151a: Spaltenbeschreibung bei [LabelFormat.GENERIC_CSV]
 * @param auxOutputNames T151a: weitere Ausgaenge (role "aux"), werden nur geprueft, nicht ausgewertet
 * @param source T151a: Katalog (Code) oder importiert (Profil-JSON pirol-model/1)
 */
data class ModelProfile(
    val id: String,
    val name: String,
    val family: String,
    val downloadUrl: String?,
    val checksum: String?,
    val fileName: String,
    val sizeBytes: Long?,
    val sampleRate: Int,
    val windowSamples: Int,
    val labelFormat: LabelFormat,
    val labelAssetPath: String? = null,
    val labelUrl: String? = null,
    val labelFileName: String? = null,
    val labelChecksum: String? = null,
    val labelSizeBytes: Long? = null,
    val scoreOutputName: String? = null,
    val scoreOutputIndex: Int,
    val embeddingOutputName: String? = null,
    val embeddingOutputIndex: Int,
    val scoreTransform: ScoreTransform,
    val speciesCount: Int,
    val licenseNote: String,
    val embeddingDbFileName: String,
    val runtime: String = "onnx",
    val inputName: String? = null,
    val genericLabels: GenericLabels? = null,
    val auxOutputNames: List<String> = emptyList(),
    val source: Source = Source.CATALOG,
    /** T156: Mindest-Konfidenz des Modells (Profil-JSON), null = nur globale Schwelle */
    val minConfidence: Float? = null
) {
    /** Groesse in MB fuer die UI (gerundet) */
    val expectedSizeMB: Int get() = ((sizeBytes ?: 0L) / 1_000_000L).toInt()

    /** Fensterlaenge in Sekunden */
    val windowSec: Float get() = windowSamples.toFloat() / sampleRate

    /** T151a: Fensterlaenge in ms (Raster fuer Analyse-Fenster und Overlap-Hop) */
    val windowMs: Int get() = kotlin.math.round(windowSamples * 1000.0 / sampleRate).toInt()

    /** T151a: Modell liefert ein Embedding (sonst keine Aehnlichkeitssuche) */
    val hasEmbedding: Boolean get() = embeddingOutputName != null || embeddingOutputIndex >= 0

    /** T151a: Herkunft des Profils */
    enum class Source { CATALOG, IMPORTED }

    /**
     * T151a: Beschreibung einer generischen Label-CSV (Profil-JSON `labels.<key>`).
     * Spalten entweder ueber den Namen (nur mit Kopfzeile) oder den 0-basierten Index.
     */
    data class GenericLabels(
        val delimiter: Char,
        val header: Boolean,
        val scientificColumn: Column,
        val commonColumn: Column? = null
    )

    /** T151a: Spaltenangabe — Name ODER Index */
    data class Column(val name: String? = null, val index: Int? = null)

    /** Format der Label-Datei */
    enum class LabelFormat {
        /** Preview 3/3.1: Semikolon-CSV `idx;id;sci_name;com_name;class;order` */
        PREVIEW_SEMICOLON,
        /** 3.0 final: Komma-CSV mit Kopfzeile `ix,birdnet_id,scientific_name,common_name,...,common_name_<lang>` */
        FINAL_CSV,
        /** T151a: importiertes Modell — Spalten/Delimiter laut [GenericLabels] */
        GENERIC_CSV
    }

    /** Umrechnung der rohen Modell-Ausgabe in Konfidenzen 0..1 */
    enum class ScoreTransform { NONE, SIGMOID, SOFTMAX }

    companion object {
        private const val ZENODO_FINAL = "https://zenodo.org/records/24162518/files/"
        private const val ZENODO_PREVIEW31 = "https://zenodo.org/records/20703646/files/"
        private const val LICENSE_BIRDNET3 = "Apache 2.0 (BirdNET+, Cornell Lab / TU Chemnitz)"

        // Gemeinsame Label-Datei aller 3.0-final-Varianten (11'725 Klassen, 36 Sprachen)
        private const val FINAL_LABEL_URL = ZENODO_FINAL + "BirdNET%2B_V3.0_Global_12K_Labels.csv"
        private const val FINAL_LABEL_FILE = "birdnet_v3_final_labels.csv"
        private const val FINAL_LABEL_SHA = "sha256:88d976477fac81127ac8ac4c215c37749d0a6537944112ea606d321317cb7640"
        private const val FINAL_LABEL_SIZE = 8_007_144L

        val V3_FINAL_FP16 = ModelProfile(
            id = "v3_final_fp16",
            name = "BirdNET 3.0 FP16 (empfohlen)",
            family = "birdnet3",
            downloadUrl = ZENODO_FINAL + "BirdNET%2B_V3.0_Global_12K_FP16.onnx",
            checksum = "sha256:9891d615f12a7a51fbc15dc40ffbd65a841dae9a2d9a80874ddc892729796dd3",
            fileName = "birdnet_v3_final_fp16.onnx",
            sizeBytes = 173_377_757L,
            sampleRate = 32000,
            windowSamples = 96000,
            labelFormat = LabelFormat.FINAL_CSV,
            labelUrl = FINAL_LABEL_URL,
            labelFileName = FINAL_LABEL_FILE,
            labelChecksum = FINAL_LABEL_SHA,
            labelSizeBytes = FINAL_LABEL_SIZE,
            scoreOutputName = "predictions",
            scoreOutputIndex = 0,
            embeddingOutputName = "embeddings_out",
            embeddingOutputIndex = 1,
            scoreTransform = ScoreTransform.NONE,
            speciesCount = 11725,
            licenseNote = LICENSE_BIRDNET3,
            embeddingDbFileName = "embeddings_v3_final_fp16.bsed"
        )

        val V3_FINAL_FP16_PRUNED = V3_FINAL_FP16.copy(
            id = "v3_final_fp16_pruned",
            name = "BirdNET 3.0 FP16 kompakt (pruned)",
            downloadUrl = ZENODO_FINAL + "BirdNET%2B_V3.0_Global_12K_FP16_pruned.onnx",
            checksum = "sha256:93ff6519683a1a9197cb3e6f931255b43f5c612212225adeeb810ec06ce9b67e",
            fileName = "birdnet_v3_final_fp16_pruned.onnx",
            sizeBytes = 71_939_197L,
            embeddingDbFileName = "embeddings_v3_final_fp16_pruned.bsed"
        )

        /** Preview 3.1 (T102); Labels identisch mit Preview 3 (Asset). Pruefsumme laut Zenodo-API (MD5). */
        val PREVIEW31_FP16 = ModelProfile(
            id = "v3_fp16",
            name = "BirdNET 3.0 Preview 3.1 FP16",
            family = "birdnet3_preview",
            downloadUrl = ZENODO_PREVIEW31 + "BirdNET%2B_V3.0-preview3.1_Global_11K_FP16.onnx",
            checksum = "md5:f0637db03f01325b46e1fc6d6043ab12",
            fileName = "birdnet_v3_fp16.onnx",
            sizeBytes = 271_554_018L,
            sampleRate = 32000,
            windowSamples = 96000,
            labelFormat = LabelFormat.PREVIEW_SEMICOLON,
            labelAssetPath = "models/birdnet_v3_labels.csv",
            scoreOutputIndex = 0,  // T122: Preview 3.1 hat das Layout von 3.0 final (Geraet: [1] = 1280 Werte)
            embeddingOutputIndex = 1,
            scoreTransform = ScoreTransform.NONE,
            speciesCount = 11560,
            licenseNote = LICENSE_BIRDNET3,
            // Bestehende Embedding-DB bleibt dem Preview-Modell zugeordnet
            embeddingDbFileName = "embeddings.bsed"
        )

        val PREVIEW31_FP32 = PREVIEW31_FP16.copy(
            id = "v3_fp32",
            name = "BirdNET 3.0 Preview 3.1 FP32",
            downloadUrl = ZENODO_PREVIEW31 + "BirdNET%2B_V3.0-preview3.1_Global_11K_FP32.onnx",
            checksum = "md5:632ffdf0b44048da4876099c2fc676ad",
            fileName = "birdnet_v3_fp32.onnx",
            sizeBytes = 541_598_502L
        )

        /** Alle bekannten Profile. Reihenfolge = Anzeige; erstes Element = Standard im Onboarding (E1). */
        val CATALOG: List<ModelProfile> = listOf(
            V3_FINAL_FP16, V3_FINAL_FP16_PRUNED, PREVIEW31_FP16, PREVIEW31_FP32
        )

        /** Profil fuer alte/unbekannte Modelldateien (vor T103a gab es nur Preview-Modelle) */
        val LEGACY_DEFAULT: ModelProfile = PREVIEW31_FP32

        /**
         * T151a: importierte Profile (Dateien `<id>.pirol.json` in filesDir/models). Setzt nur der ModelManager
         * (beim Start und nach Import/Loeschen).
         */
        @Volatile
        var imported: List<ModelProfile> = emptyList()

        /** Katalog + importierte Profile */
        val all: List<ModelProfile> get() = CATALOG + imported

        fun byId(id: String?): ModelProfile? = all.firstOrNull { it.id == id }

        fun byFileName(fileName: String): ModelProfile? = all.firstOrNull { it.fileName == fileName }

        fun bySize(sizeBytes: Long): ModelProfile? = CATALOG.firstOrNull { it.sizeBytes == sizeBytes }

        /**
         * Ordnet eine installierte Modelldatei einem Profil zu.
         * Reihenfolge: Katalog-Dateiname (Download/Import werden so benannt) → exakte Groesse
         * (z.B. vor T103a importierte Originaldatei) → Preview (Legacy).
         */
        fun resolve(fileName: String, sizeBytes: Long): ModelProfile =
            byFileName(fileName) ?: bySize(sizeBytes) ?: LEGACY_DEFAULT
    }
}
