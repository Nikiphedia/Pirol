package ch.etasystems.pirol.ui.analysis

/**
 * T203: Zeitfenster eines manuell hinzugefuegten Eintrags in der Analyse.
 * Reine Funktion (unit-testbar): Abspielposition p +/- [HALF_WINDOW_SEC].
 */
data class ManualEntryTime(
    val chunkStartSec: Float,
    val chunkEndSec: Float,
    val timestampMs: Long,
    val hasPosition: Boolean
) {
    companion object {
        const val HALF_WINDOW_SEC = 10f

        /**
         * @param positionSec Abspielposition, session-relativ (null = unbekannt)
         * @param durationSec Dauer der Aufnahme (<= 0 = keine Aufnahme)
         * @param sessionStartMs Session-Start in epoch ms (null = unbekannt)
         * @param nowMs Rueckfall-Zeitstempel ohne Position
         */
        fun compute(positionSec: Float?, durationSec: Float, sessionStartMs: Long?, nowMs: Long): ManualEntryTime {
            if (positionSec == null || durationSec <= 0f || sessionStartMs == null) {
                return ManualEntryTime(0f, 0f, nowMs, false)
            }
            val p = positionSec.coerceIn(0f, durationSec)
            return ManualEntryTime(
                chunkStartSec = (p - HALF_WINDOW_SEC).coerceAtLeast(0f),
                chunkEndSec = (p + HALF_WINDOW_SEC).coerceAtMost(durationSec),
                timestampMs = sessionStartMs + (p * 1000f).toLong(),
                hasPosition = true
            )
        }
    }
}
