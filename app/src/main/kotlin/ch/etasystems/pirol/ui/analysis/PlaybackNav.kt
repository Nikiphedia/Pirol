package ch.etasystems.pirol.ui.analysis

import ch.etasystems.pirol.ml.DetectionResult

/**
 * T210: Reine Logik fuer Analyse-Wiedergabe (Vor/Zurueck zwischen Detektionen, Tippen -> Sekunde).
 */
object PlaybackNav {

    /** T301: Zeiger bei Memo-Wiedergabe = Memo-Beginn (Session-Sekunde) + Position in der Memo-Datei; ohne Beginn kein Zeiger. */
    fun memoPlayheadSec(posInMemoSec: Float?, memoStartSec: Float?): Float? =
        if (posInMemoSec == null || memoStartSec == null) null else memoStartSec + posInMemoSec

    /** T303: Wiedergabe-Schluessel eines Memos (Memo-Block oder Artkarte, `memo:<id>`) */
    fun isMemoKey(key: String?): Boolean = key != null && key.startsWith(ch.etasystems.pirol.audio.PlaybackKeys.memo(""))

    /**
     * T303 (Befund 9): Zeiger fuer die laufende Wiedergabe [playingKey]. Kein Memo -> [posSec] unveraendert.
     * Memo (auch von der Artkarte gestartet): Session-Beginn aus [remembered] (Memo-Block, T301) oder aus [items]
     * (gleicher Schluessel); ohne bekannte Session-Lage kein Zeiger (nie die Position in der Memo-Datei).
     */
    fun playheadSec(posSec: Float?, playingKey: String?, remembered: Pair<String, Float?>?, items: () -> List<MemoItem>): Float? {
        if (!isMemoKey(playingKey)) return posSec
        val start = if (remembered != null && remembered.first == playingKey) remembered.second
        else items().firstOrNull { ch.etasystems.pirol.audio.PlaybackKeys.memo(it.key) == playingKey }?.sessionStartSec
        return memoPlayheadSec(posSec, start)
    }
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
    /** T211: Anteil 0..1 der Position im Ausschnitt; null ausserhalb des Ausschnitts (kein Zeiger). */
    fun playheadFraction(posSec: Float?, viewStartSec: Float, viewDurSec: Float): Float? {
        if (posSec == null || viewDurSec <= 0f) return null
        val f = (posSec - viewStartSec) / viewDurSec
        return if (f in 0f..1f) f else null
    }

    fun tapToSec(x: Float, left: Float, width: Float, viewStartSec: Float, viewDurSec: Float, totalSec: Float): Float? {
        if (width <= 0f || viewDurSec <= 0f) return null
        val frac = ((x - left) / width).coerceIn(0f, 1f)
        return (viewStartSec + frac * viewDurSec).coerceIn(0f, totalSec.coerceAtLeast(0f))
    }
}
