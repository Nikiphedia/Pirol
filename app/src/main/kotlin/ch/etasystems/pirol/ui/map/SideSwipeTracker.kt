package ch.etasystems.pirol.ui.map

/**
 * T119: Erkennt ein Wischen in eine Richtung (z.B. von der linken Randzone zur Mitte).
 * Summiert die Horizontal-Deltas einer Geste; meldet einmalig true, sobald die Schwelle erreicht ist.
 * Rueckwaertsbewegung verringert die Summe (Abbruch durch Zurueckwischen moeglich).
 */
class SideSwipeTracker(private val thresholdPx: Float) {
    private var total = 0f
    private var fired = false

    fun reset() {
        total = 0f
        fired = false
    }

    /** @param dx Delta in Zielrichtung (positiv = Richtung Oeffnen). @return true genau einmal beim Erreichen der Schwelle. */
    fun onDrag(dx: Float): Boolean {
        if (fired) return false
        total += dx
        if (total >= thresholdPx) {
            fired = true
            return true
        }
        return false
    }

    companion object {
        /** Schwelle in dp, ab der das Wischen als Oeffnen/Schliessen gilt. */
        const val OPEN_THRESHOLD_DP = 40f
    }
}
