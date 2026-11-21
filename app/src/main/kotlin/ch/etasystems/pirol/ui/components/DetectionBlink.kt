package ch.etasystems.pirol.ui.components

/**
 * T178: Ausloese-Logik fuer das Aufblinken einer Artkarte bei erneuter Erkennung.
 *
 * Reine Funktion, ohne Compose, damit unit-testbar.
 */
object DetectionBlink {
    /** Ein Treffer blinkt nur, wenn er hoechstens so alt ist (Karte war ausserhalb des Bildschirms). */
    const val CATCH_UP_WINDOW_MS = 3_000L

    /**
     * @param signalMs Zeitpunkt des letzten Treffers der Karte (0 = nie getroffen, z.B. geladene Session)
     * @param handledMs Treffer-Zeitpunkt, fuer den schon geblinkt wurde (0 = noch nie)
     * @param nowMs aktuelle Zeit
     */
    fun shouldBlink(signalMs: Long, handledMs: Long, nowMs: Long): Boolean =
        signalMs > 0L &&
            signalMs > handledMs &&
            nowMs - signalMs <= CATCH_UP_WINDOW_MS
}
