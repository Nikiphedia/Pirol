package ch.etasystems.pirol.ml

/** Standard-Chunk-Dauer in Millisekunden (BirdNET-Fenster = 3s) */
const val CHUNK_DURATION_MS = 3000L

/**
 * Konfiguration fuer die BirdNET-Inferenz-Pipeline.
 *
 * Steuert Confidence-Schwelle, maximale Ergebnisanzahl pro 3s-Chunk,
 * optionale regionale Artenfilterung und Inference-Intervall (Energiesparmodus).
 */
data class InferenceConfig(
    /** Minimale Confidence (0.0 – 1.0) damit eine Detektion akzeptiert wird */
    val confidenceThreshold: Float = 0.5f,

    /** Maximale Anzahl Ergebnisse pro 3s-Chunk (1 = Top-1, 2-5 = Multi-Label B2) */
    val topK: Int = 3,

    /**
     * Detection-Threshold fuer den TopK-Classifier (T71: Multi-Label).
     * Tiefer als confidenceThreshold → mehr Kandidaten, Cluster-Logik filtert weiter.
     * 0.3f = Standardwert, 0.2f = sensitiv, 0.5f = strikt.
     */
    val topKThreshold: Float = 0.3f,

    /** Region-ID fuer Artenfilter (null = kein Filter, "dach" = DACH-Filter) */
    val regionFilter: String? = "ch_breeding",

    /** true = nur regionale Arten anzeigen, false = alle (ungewoehnliche markiert) */
    val showOnlyFiltered: Boolean = true,

    /** Mindest-Intervall in ms zwischen zwei Inferences (Energiesparmodus) */
    val inferenceIntervalMs: Long = CHUNK_DURATION_MS,

    /** Hochpassfilter (~200Hz Butterworth 2. Ordnung) gegen Wind/Trittschall (T34) */
    val highpassEnabled: Boolean = true
) {
    companion object {
        /** Standard-Konfiguration: ausgewogen (TopK=3, Threshold=0.3) */
        val DEFAULT = InferenceConfig()

        /** Sensitiv: niedrige Schwelle, mehr Ergebnisse – fuer stille Umgebungen */
        val SENSITIVE = InferenceConfig(confidenceThreshold = 0.3f, topK = 5, topKThreshold = 0.2f)

        /** Strikt: hohe Schwelle, wenige Ergebnisse – fuer laute Umgebungen */
        val STRICT = InferenceConfig(confidenceThreshold = 0.7f, topK = 3, topKThreshold = 0.5f)

        /** Sparsam: Inference alle 15s – spart Akku im Feld */
        val POWER_SAVE = InferenceConfig(inferenceIntervalMs = 15000L)

        /** Ultra-Sparsam: Inference alle 30s – maximale Akkulaufzeit */
        val ULTRA_SAVE = InferenceConfig(inferenceIntervalMs = 30000L)
    }
}
