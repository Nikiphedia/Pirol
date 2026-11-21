package ch.etasystems.pirol.ui.components

import java.util.Locale
import kotlin.math.abs

/** T160: Hoechstens so oft (ms) wird das Live-Sonogramm neu gezeichnet (~25 fps). */
internal const val SPECTROGRAM_RENDER_INTERVAL_MS = 40L

/** T160: Aendert sich der dB-Bereich seit dem letzten Neuaufbau um mehr als das (dB), wird neu aufgebaut. */
internal const val RANGE_REBUILD_TOLERANCE_DB = 1.5f

/**
 * T160: Spalten-Ringpuffer-Logik fuer das Rolling-Sonogramm (rein, ohne Android).
 *
 * [head] = naechste zu beschreibende Spalte = aelteste sichtbare Spalte. Die Anzeige-Reihenfolge
 * (alt nach neu) ist [head, width) gefolgt von [0, head).
 */
internal class ColumnRing(val width: Int) {
    var head: Int = 0
        private set

    init { require(width > 0) }

    fun reset() { head = 0 }

    /**
     * Beschreibt [count] neue Spalten ab [head] und schiebt [head] weiter.
     * Rueckgabe: Laeufe (startSpalte, laenge), hoechstens zwei (Umbruch am Ende).
     */
    fun write(count: Int): List<IntArray> {
        val n = count.coerceIn(0, width)
        if (n == 0) return emptyList()
        val first = minOf(n, width - head)
        val runs = ArrayList<IntArray>(2)
        runs.add(intArrayOf(head, first))
        if (n > first) runs.add(intArrayOf(0, n - first))
        head = (head + n) % width
        return runs
    }

    /**
     * Zeichen-Segmente (srcStart, srcLen) in Anzeige-Reihenfolge; ihr Ziel-Offset ist die Summe
     * der vorherigen Laengen. Bei head == 0 genau ein Segment.
     */
    fun drawSegments(): List<IntArray> =
        if (head == 0) listOf(intArrayOf(0, width))
        else listOf(intArrayOf(head, width - head), intArrayOf(0, head))
}

/** T160: Soll das Bitmap komplett neu aufgebaut werden, weil sich der dB-Bereich zu stark verschoben hat? */
internal fun rangeNeedsRebuild(lastMin: Float, lastMax: Float, minDb: Float, maxDb: Float): Boolean =
    lastMin.isNaN() || lastMax.isNaN() ||
        abs(minDb - lastMin) > RANGE_REBUILD_TOLERANCE_DB || abs(maxDb - lastMax) > RANGE_REBUILD_TOLERANCE_DB

/**
 * T160: Messwerte fuer die Render-Last. Liefert alle [intervalMs] eine Log-Zeile
 * `T160_RENDER fps=… frame_ms_median=… cols_per_s=…`; null dazwischen.
 * Eine Pause (> 2 s ohne Zeichnen, z.B. Ansicht unsichtbar) startet das Fenster neu.
 */
internal class RenderStats(private val intervalMs: Long = 10_000L) {
    private val frameMs = ArrayList<Float>()
    private var columns = 0L
    private var startMs = -1L
    private var lastMs = -1L

    fun record(nowMs: Long, frameMillis: Float, newColumns: Int): String? {
        if (lastMs >= 0 && nowMs - lastMs > 2000L) reset()
        lastMs = nowMs
        if (startMs < 0) startMs = nowMs
        frameMs.add(frameMillis)
        columns += newColumns
        val elapsed = nowMs - startMs
        if (elapsed < intervalMs) return null
        val sec = elapsed / 1000.0
        val sorted = frameMs.sorted()
        val line = String.format(
            Locale.US, "T160_RENDER fps=%.1f frame_ms_median=%.2f cols_per_s=%.1f",
            frameMs.size / sec, sorted[sorted.size / 2], columns / sec
        )
        reset()
        return line
    }

    private fun reset() {
        frameMs.clear(); columns = 0; startMs = -1
    }
}
