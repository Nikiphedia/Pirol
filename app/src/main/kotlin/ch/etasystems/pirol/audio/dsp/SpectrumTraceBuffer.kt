package ch.etasystems.pirol.audio.dsp

import kotlin.math.ceil

/**
 * T201: Rollende Peak-Spuren des Spektrums. Je Zeitscheibe von [sliceSec] Sekunden wird das
 * Maximum der Spektren (dB) gehalten; die letzten [durationSec] Sekunden bleiben als Schichten
 * erhalten, aeltere werden verworfen. Alpha faellt linear mit dem Alter (juengste Schicht am
 * deutlichsten). Dauer 0 = aus (leert den Puffer). Nicht thread-safe (Aufrufer synchronisiert).
 */
class SpectrumTraceBuffer(
    private val sliceSec: Float = SLICE_SEC,
    private val maxClosedSlices: Int = MAX_SLICES
) {
    /** Eine Schicht: Maximum-Spektrum und Deckkraft 0..1. */
    class Layer(val values: FloatArray, val alpha: Float)

    private var durationSec = 0f
    private val closed = ArrayDeque<FloatArray>()
    private var open: FloatArray? = null
    private var openElapsed = 0f

    /** Anzahl abgeschlossener Scheiben, die bei [durationSec] gehalten werden. */
    private fun keepCount(): Int =
        if (durationSec <= 0f) 0 else ceil(durationSec / sliceSec).toInt().coerceIn(1, maxClosedSlices)

    val enabled: Boolean get() = durationSec > 0f

    /** Dauer setzen; 0 schaltet aus und leert, kleinere Dauer verwirft sofort die aeltesten Scheiben. */
    fun setDuration(sec: Float) {
        durationSec = maxOf(0f, sec)
        if (durationSec <= 0f) clear() else trim()
    }

    fun clear() {
        closed.clear()
        open = null
        openElapsed = 0f
    }

    /** Spektrum [frame] (dB) einfuegen; [dtSec] = Zeit seit dem letzten Frame. */
    fun add(frame: FloatArray, dtSec: Float) {
        if (durationSec <= 0f || frame.isEmpty()) return
        if (closed.isNotEmpty() && closed.last().size != frame.size) closed.clear()
        val cur = open
        if (cur == null || cur.size != frame.size) {
            // Neue Frame-Groesse (FFT-Wechsel): alte Schichten passen nicht mehr
            if (cur != null) closed.clear()  // offene Scheibe passt nicht mehr
            open = frame.copyOf()
            openElapsed = 0f
        } else {
            for (i in frame.indices) if (frame[i] > cur[i]) cur[i] = frame[i]
        }
        openElapsed += dtSec
        if (openElapsed >= sliceSec) {
            closed.addLast(open!!)
            open = null
            openElapsed = 0f
            trim()
        }
    }

    private fun trim() {
        val keep = keepCount()
        while (closed.size > keep) closed.removeFirst()
    }

    /**
     * Schichten, juengste zuerst (offene Scheibe, dann abgeschlossene), als Kopien. Alpha je
     * Alter k (0 = juengste): 1 - k / (Anzahl + 1), also immer > 0.
     */
    fun layers(): List<Layer> {
        val src = ArrayList<FloatArray>(closed.size + 1)
        open?.let { src.add(it) }
        for (i in closed.indices.reversed()) src.add(closed[i])
        val total = keepCount() + 1
        return src.mapIndexed { k, v -> Layer(v.copyOf(), 1f - k.toFloat() / total) }
    }

    companion object {
        const val SLICE_SEC = 0.5f
        const val MAX_SLICES = 60
    }
}
