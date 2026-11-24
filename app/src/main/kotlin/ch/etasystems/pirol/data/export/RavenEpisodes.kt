package ch.etasystems.pirol.data.export

import ch.etasystems.pirol.ml.DetectionResult
import ch.etasystems.pirol.ui.analysis.DetectionOccurrences

/**
 * T259: Raven-Zeilen pro Episode statt pro Cluster.
 *
 * Fenster eines Clusters = Anker-Fenster (chunkStartSec, Laenge chunkEndSec - chunkStartSec) plus alle
 * absorbierten Fenster aus CLUSTER_MERGED (`start:` = Beginn, Laenge wie der Anker, `conf:` = Score).
 * Episode = nach Beginn sortierte Fenster, bei denen die Luecke zwischen dem bisherigen Ende und dem
 * naechsten Beginn hoechstens eine Fensterlaenge betraegt (Beruehren und Ueberlappen eingeschlossen).
 *
 * Rueckfall (eine Zeile wie bisher, bestes Fenster T186): manuelle Eintraege, unplausible Fensterlaenge
 * oder mindestens ein Merge-Event ohne `start:` (Sessions vor T181) — dort laesst sich nicht jedes Fenster
 * platzieren. Reine Funktion ohne Android-Abhaengigkeit.
 */
object RavenEpisodes {

    /** Gleiche Startzeit (Rundung %.3f) = dasselbe Fenster (doppeltes Event). */
    private const val SAME_START_SEC = 0.01f

    /** Float-Toleranz beim Luecken-Vergleich (Luecke genau eine Fensterlaenge gehoert noch dazu). */
    private const val GAP_EPS_SEC = 0.001f

    /**
     * Eine Raven-Zeile.
     * @param startSec   Beginn (session-relativ)
     * @param endSec     Ende (session-relativ)
     * @param confidence Maximum der bekannten Fenster-Scores; null = unbekannt (leere Zelle)
     * @param windows    Anzahl Fenster der Zeile
     */
    data class Row(val startSec: Float, val endSec: Float, val confidence: Float?, val windows: Int)

    private data class Window(val start: Float, val end: Float, val conf: Float?)

    fun rows(detection: DetectionResult, merges: List<DetectionOccurrences.MergeInfo>): List<Row> {
        val window = detection.chunkEndSec - detection.chunkStartSec
        val placeable = !detection.manualEntry && window in 0.1f..30f && merges.all { it.startSec != null }
        if (!placeable) {
            return listOf(Row(detection.bestWindowStartSec, detection.bestWindowEndSec, detection.confidence, 1 + merges.size))
        }
        // Anker-Score nach derselben Regel wie die Vorkommen-Anzeige (T181/T186): nur sicher, wenn kein
        // spaeteres Fenster das Cluster-Maximum traegt
        val anchorConf = DetectionOccurrences.of(detection, merges).first().confidence
        val all = mutableListOf(Window(detection.chunkStartSec, detection.chunkStartSec + window, anchorConf))
        for (m in merges) {
            val s = m.startSec ?: continue
            all += Window(s, s + window, m.confidence)
        }
        // Doppelte Fenster (gleicher Beginn) zusammenlegen, bekannten Score behalten
        val windows = all.sortedBy { it.start }.fold(mutableListOf<Window>()) { acc, w ->
            val last = acc.lastOrNull()
            if (last != null && kotlin.math.abs(w.start - last.start) <= SAME_START_SEC) {
                acc[acc.size - 1] = last.copy(conf = listOfNotNull(last.conf, w.conf).maxOrNull())
            } else acc += w
            acc
        }
        val rows = mutableListOf<Row>()
        var cur = mutableListOf(windows.first())
        var curEnd = windows.first().end
        fun flush() {
            rows += Row(cur.first().start, curEnd, cur.mapNotNull { it.conf }.maxOrNull(), cur.size)
        }
        for (w in windows.drop(1)) {
            if (w.start - curEnd <= window + GAP_EPS_SEC) {
                cur += w
                curEnd = maxOf(curEnd, w.end)
            } else {
                flush()
                cur = mutableListOf(w)
                curEnd = w.end
            }
        }
        flush()
        return rows
    }
}
