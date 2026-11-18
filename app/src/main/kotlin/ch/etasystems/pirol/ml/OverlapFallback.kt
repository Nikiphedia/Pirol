package ch.etasystems.pirol.ml

/**
 * Auto-Fallback fuer die Fenster-Ueberlappung (T110).
 *
 * Misst die Inference-Dauer pro Fenster. Ist der gleitende Median groesser als
 * [loadFactor] x Zeitbudget (Hop bzw. laengeres Energiespar-Intervall) oder wurden seit der
 * letzten Stufe mindestens [dropThreshold] Fenster verworfen (Stau), wird die Ueberlappung
 * eine Stufe reduziert (67 → 50 → 0). Reine Logik, kein Android — JVM-testbar.
 */
class OverlapFallback(
    private val windowSize: Int = 10,
    private val minSamples: Int = 5,
    private val loadFactor: Double = 0.8,
    private val dropThreshold: Int = 2
) {
    companion object {
        /** Erlaubte Stufen der Ueberlappung in Prozent */
        val LEVELS = listOf(0, 50, 67)

        /** Beliebigen Wert auf die naechst-tiefere erlaubte Stufe abbilden */
        fun normalize(percent: Int): Int = when {
            percent >= 67 -> 67
            percent >= 50 -> 50
            else -> 0
        }

        /** Hop (Abstand der Fensterstarts) in ms: 0 % = 3000, 50 % = 1500, 67 % = 1000 */
        fun hopMsFor(percent: Int): Int = when (normalize(percent)) {
            67 -> 1000
            50 -> 1500
            else -> CHUNK_DURATION_MS.toInt()
        }

        /** Naechst-tiefere Stufe */
        fun stepDown(percent: Int): Int = when (normalize(percent)) {
            67 -> 50
            else -> 0
        }
    }

    private val durations = ArrayDeque<Long>()
    private var dropsAtLevelStart = 0

    /** Median der letzten Messungen in ms (0 wenn keine) */
    fun medianMs(): Long {
        if (durations.isEmpty()) return 0L
        val sorted = durations.sorted()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2
    }

    /**
     * Eine Messung auswerten.
     *
     * @param currentOverlap Ueberlappung mit der das gemessene Fenster erzeugt wurde
     * @param budgetMs verfuegbare Zeit pro Fenster (max(Hop, Energiespar-Intervall))
     * @param inferMs gemessene Inference-Dauer
     * @param totalDropped Gesamtzahl verworfener Fenster in dieser Session
     * @return neue (reduzierte) Ueberlappung oder null wenn keine Aenderung
     */
    fun evaluate(currentOverlap: Int, budgetMs: Long, inferMs: Long, totalDropped: Int): Int? {
        durations.addLast(inferMs)
        while (durations.size > windowSize) durations.removeFirst()
        if (normalize(currentOverlap) == 0) return null

        val tooSlow = durations.size >= minSamples && medianMs() > loadFactor * budgetMs
        val queued = totalDropped - dropsAtLevelStart >= dropThreshold
        if (!tooSlow && !queued) return null

        // Neue Stufe: Messreihe neu beginnen
        durations.clear()
        dropsAtLevelStart = totalDropped
        return stepDown(currentOverlap)
    }

    fun reset() {
        durations.clear()
        dropsAtLevelStart = 0
    }
}
