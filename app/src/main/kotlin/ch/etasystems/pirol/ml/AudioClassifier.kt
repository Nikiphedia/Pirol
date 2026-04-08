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

    /** Embedding extrahieren (null wenn nicht unterstuetzt) */
    fun extractEmbedding(samples: FloatArray, sampleRate: Int): FloatArray?

    /** Session zuruecksetzen (nach Modell-Wechsel) */
    fun resetSession()

    /** Anzahl Labels */
    val labelCount: Int

    /** Modell-Name fuer UI */
    val modelName: String

    /** Klassifizierungsergebnis */
    data class ClassifierResult(
        val scientificName: String,
        val commonName: String,
        val confidence: Float,
        val timeStartSec: Float,
        val timeEndSec: Float
    )
}
