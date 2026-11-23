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
     * T225: Untergrenze fuer Alternativen (Kandidaten). Arten zwischen dieser Grenze und
     * confidenceThreshold erscheinen als Alternativen der Detektionen desselben Fensters
     * (hoechstens topK). Ersetzt topKThreshold; wirkt nie ueber confidenceThreshold hinaus.
     */
    val alternativesFloor: Float = 0.10f,

    /** Region-ID fuer Artenfilter (null = kein Filter, z.B. "ch_breeding", "central_europe") */
    val regionFilter: String? = "ch_breeding",

    /** true = nur regionale Arten anzeigen, false = alle (ungewoehnliche markiert) */
    val showOnlyFiltered: Boolean = true,

    /** Mindest-Intervall in ms zwischen zwei Inferences (Energiesparmodus) */
    val inferenceIntervalMs: Long = CHUNK_DURATION_MS,

    /** Hochpassfilter (~200Hz Butterworth 2. Ordnung) gegen Wind/Trittschall (T34) */
    val highpassEnabled: Boolean = true,

    /** Ueberlappung der 3-s-Analysefenster in Prozent: 0 (Hop 3 s), 50 (1.5 s), 67 (1 s) — T110 */
    val overlapPercent: Int = 0,

    /** T190: Saison der Artenliste beachten (Default aus) */
    val respectListSeason: Boolean = false,

    /**
     * T231: groesster Hop in ms (Pref `pirol_max_hop_ms`, Default 3 s). Wirkt nur bei Fenstern ueber
     * diesem Wert (Perch 5 s: Fenster alle 3 s statt alle 5 s); BirdNET (3 s) bleibt unveraendert.
     */
    val maxHopMs: Int = DEFAULT_MAX_HOP_MS
) {
    /** T231: Hop fuer ein Modellfenster bei gegebener Ueberlappung (Overlap relativ zum Fenster, hoechstens [maxHopMs]) */
    fun hopMsFor(overlap: Int, windowMs: Int): Int = OverlapFallback.hopMsFor(overlap, windowMs, maxHopMs)

    companion object {
        /** T231: Default des groessten Hops (User: "Perch alle drei Sekunden scannen") */
        const val DEFAULT_MAX_HOP_MS = 3000

        /** T231: waehlbare Werte in den Einstellungen */
        val MAX_HOP_OPTIONS_MS = listOf(3000, 4000, 5000)

        /** T156: wirksame Schwelle = max(globale Schwelle, Mindest-Konfidenz des Modells) */
        fun effectiveThreshold(global: Float, modelMin: Float?): Float =
            if (modelMin != null && modelMin > global) modelMin else global

        /** T225: Classifier-Schwelle = Untergrenze der Alternativen (hoechstens wirksame Detektionsschwelle) */
        fun classifierThreshold(confidence: Float, alternativesFloor: Float, modelMin: Float?): Float =
            // T231: die Modell-Mindestkonfidenz hebt nur die Detektionsschwelle, nicht die Alternativen-Untergrenze.
            // Bei Softmax (Perch, minConfidence 0.8) teilen sich mehrere Arten die Wahrscheinlichkeit; die
            // Konkurrenten unter 0.8 sind genau die Alternativen. Ohne minConfidence (BirdNET) unveraendert.
            minOf(alternativesFloor, effectiveThreshold(confidence, modelMin))

        /** Standard-Konfiguration: ausgewogen (TopK=3, Threshold=0.3) */
        val DEFAULT = InferenceConfig()

        /** Sensitiv: niedrige Schwelle, mehr Ergebnisse – fuer stille Umgebungen */
        val SENSITIVE = InferenceConfig(confidenceThreshold = 0.3f, topK = 5, alternativesFloor = 0.05f)

        /** Strikt: hohe Schwelle, wenige Ergebnisse – fuer laute Umgebungen */
        val STRICT = InferenceConfig(confidenceThreshold = 0.7f, topK = 3, alternativesFloor = 0.20f)

        /** Sparsam: Inference alle 15s – spart Akku im Feld */
        val POWER_SAVE = InferenceConfig(inferenceIntervalMs = 15000L)

        /** Ultra-Sparsam: Inference alle 30s – maximale Akkulaufzeit */
        val ULTRA_SAVE = InferenceConfig(inferenceIntervalMs = 30000L)
    }
}
