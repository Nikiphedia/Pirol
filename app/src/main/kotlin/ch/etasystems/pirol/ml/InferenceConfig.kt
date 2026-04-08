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

    /** Maximale Anzahl Ergebnisse pro 3s-Chunk */
    val topK: Int = 5,

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
        /** Standard-Konfiguration: ausgewogen */
        val DEFAULT = InferenceConfig()

        /** Sensitiv: niedrige Schwelle, mehr Ergebnisse – fuer stille Umgebungen */
        val SENSITIVE = InferenceConfig(confidenceThreshold = 0.3f, topK = 10)

        /** Strikt: hohe Schwelle, wenige Ergebnisse – fuer laute Umgebungen */
        val STRICT = InferenceConfig(confidenceThreshold = 0.7f, topK = 3)

        /** Sparsam: Inference alle 15s – spart Akku im Feld */
        val POWER_SAVE = InferenceConfig(inferenceIntervalMs = 15000L)

        /** Ultra-Sparsam: Inference alle 30s – maximale Akkulaufzeit */
        val ULTRA_SAVE = InferenceConfig(inferenceIntervalMs = 30000L)
    }
}
