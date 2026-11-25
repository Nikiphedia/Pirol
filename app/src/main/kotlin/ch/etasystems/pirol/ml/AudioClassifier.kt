package ch.etasystems.pirol.ml

/**
 * Abstraktion fuer Audio-Klassifizierer.
 * Ermoeglicht verschiedene Modelle (BirdNET V3.0, V2.4, Custom).
 */
interface AudioClassifier {
    /** Ob das Modell geladen werden kann */
    fun isModelAvailable(): Boolean

    /** Klassifizierung durchfuehren */
    fun classify(
        samples: FloatArray,
        sampleRate: Int,
        topK: Int = 5,
        threshold: Float = 0.5f
    ): List<ClassifierResult>

    /**
     * Klassifizierung + Embedding aus EINEM Modell-Durchlauf (T61).
     * Default: nur klassifizieren, kein Embedding — Implementierungen mit
     * kombiniertem Output (BirdNET V3) ueberschreiben das.
     */
    fun classifyWithEmbedding(
        samples: FloatArray,
        sampleRate: Int,
        topK: Int = 5,
        threshold: Float = 0.5f
    ): ClassificationOutput = ClassificationOutput(classify(samples, sampleRate, topK, threshold), null)

    /**
     * T250/N3: alle Scores direkt als Array (ohne je Art ein Ergebnisobjekt, ohne Sortierung).
     * null = nicht unterstuetzt (Aufrufer faellt auf [classify] zurueck) oder Fehler.
     */
    fun scoreAll(samples: FloatArray, sampleRate: Int): ScoreTable? = null

    /** Embedding extrahieren (null wenn nicht unterstuetzt) */
    fun extractEmbedding(samples: FloatArray, sampleRate: Int): FloatArray?

    /** Session zuruecksetzen (nach Modell-Wechsel) */
    fun resetSession()

    /** T187: Modell-Session freigeben, falls keine Inferenz laeuft (Standard: nichts zu tun). */
    fun releaseIfIdle(): Boolean = false

    /** Anzahl Labels */
    val labelCount: Int

    /** Modell-Name fuer UI */
    val modelName: String

    /** T103a: Profil-ID des aktiven Modells (null = unbekannt/Test) */
    val modelId: String? get() = null

    /** T103a: Abtastrate, die das aktive Modell erwartet */
    val inputSampleRate: Int get() = 32000

    /** T151a: Fensterlaenge des aktiven Modells in ms (Analyse-Fenster, Overlap-Hop) */
    val windowMs: Int get() = CHUNK_DURATION_MS.toInt()

    /** T156: Mindest-Konfidenz des aktiven Modells (null = nur globale Schwelle) */
    val minConfidence: Float? get() = null

    /** T293: kleinste Quell-Abtastrate des Modells (null = keine Grenze); kleinere Quellen werden nicht analysiert */
    val minSourceRate: Int? get() = null

    /** T151a: Modell liefert ein Embedding (sonst keine Aehnlichkeitssuche) */
    val hasEmbedding: Boolean get() = true

    /** T103a: wissenschaftliche Namen aller Labels (Unterstrich-Format), leer wenn nicht geladen */
    val labelScientificNames: Set<String> get() = emptySet()

    /** T250/N3: Scores je Label-Index; [names] = wissenschaftliche Namen (gleiche Reihenfolge, wiederverwendet) */
    class ScoreTable(val scores: FloatArray, val names: List<String>)

    /** Ergebnis von [classifyWithEmbedding]: Treffer + optionales Embedding (roh, nicht normalisiert) */
    class ClassificationOutput(
        val results: List<ClassifierResult>,
        val embedding: FloatArray?,
        /** T261: Mensch-Score aus dem Rohausgang ([HumanLabels.score]); null = Modell kennt keine Menschen */
        val humanScore: Float? = null
    )

    /** Klassifizierungsergebnis */
    data class ClassifierResult(
        val scientificName: String,
        val commonName: String,
        val confidence: Float,
        val timeStartSec: Float,
        val timeEndSec: Float
    )
}
