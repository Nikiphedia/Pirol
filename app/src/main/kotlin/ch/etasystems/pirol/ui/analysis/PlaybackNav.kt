package ch.etasystems.pirol.ui.analysis

import ch.etasystems.pirol.ml.DetectionResult

/**
 * T210: Reine Logik fuer Analyse-Wiedergabe (Vor/Zurueck zwischen Detektionen, Tippen -> Sekunde).
 */
object PlaybackNav {
    /** Zeiten, die naeher beieinander liegen, gelten als gleich (kein Haengenbleiben auf derselben Detektion). */
    const val EPS_SEC = 0.05f

    /** Navigierbare Detektionen nach Startzeit (bestes Fenster) sortiert; manuelle Eintraege haben keinen Ausschnitt. */
    fun sortedByTime(detections: List<DetectionResult>): List<DetectionResult> =
        detections.filter { !it.manualEntry }.sortedBy { it.bestWindowStartSec }

    /** Erste Detektion mit Start nach [fromSec]; null am Ende. [sorted] kommt aus [sortedByTime]. */
    fun next(sorted: List<DetectionResult>, fromSec: Float): DetectionResult? =
        sorted.firstOrNull { it.bestWindowStartSec > fromSec + EPS_SEC }

    /** Letzte Detektion mit Start vor [fromSec]; null am Anfang. */
    fun previous(sorted: List<DetectionResult>, fromSec: Float): DetectionResult? =
        sorted.lastOrNull { it.bestWindowStartSec < fromSec - EPS_SEC }

    /**
     * Tipp-Position -> Session-Sekunde. [left]/[width] = Zeichenbereich in Pixeln,
     * [viewStartSec]/[viewDurSec] = sichtbarer Zeitbereich. Ergebnis auf [0, totalSec] begrenzt.
     */
    fun tapToSec(x: Float, left: Float, width: Float, viewStartSec: Float, viewDurSec: Float, totalSec: Float): Float? {
        if (width <= 0f || viewDurSec <= 0f) return null
        val frac = ((x - left) / width).coerceIn(0f, 1f)
        return (viewStartSec + frac * viewDurSec).coerceIn(0f, totalSec.coerceAtLeast(0f))
    }
}
