package ch.etasystems.pirol.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * T193: Min/Max-Ringpuffer fuer das Live-Oszillogramm.
 *
 * Statt der Rohsamples wird pro [binSize] Samples nur ein Min/Max-Paar gehalten (normiert auf
 * -1..1). Das reicht fuer die Anzeige und kostet pro Chunk nur einen Durchlauf ueber die Samples.
 * Gefuellt wird nur, solange [active] true ist (Oszillogramm-Modus sichtbar); sonst keine Rechenlast.
 *
 * Thread-safe ueber @Synchronized (Schreiber: Collector-Job, Leser: Draw-Phase).
 *
 * @param maxBins Kapazitaet in Bins (Live: 3072 = ~32 s bei 48 kHz und binSize 512)
 * @param binSize Samples pro Bin (entspricht dem Mel-Hop, damit das Zeitfenster dem Sonogramm gleicht)
 */
class WaveformState(val maxBins: Int = 3072, val binSize: Int = 512) {
    private val mins = FloatArray(maxBins)
    private val maxs = FloatArray(maxBins)
    private var head = 0
    private var count = 0
    private var curMin = Float.MAX_VALUE
    private var curMax = -Float.MAX_VALUE
    private var curCount = 0

    /** true = Oszillogramm wird angezeigt, der Collector soll [append] aufrufen. */
    @Volatile var active: Boolean = false
        private set

    /** Draw-Dependency: aendert sich bei jedem [append]/[clear]. */
    var version by mutableIntStateOf(0)
        private set

    /** true, solange Bins vorhanden sind (beobachtbar, nur bei Wechsel geschrieben). */
    var hasData by mutableStateOf(false)
        private set

    /** Modus ein-/ausschalten. Beim Wechsel wird der Puffer geleert (kein Loch im Verlauf). */
    @Synchronized
    fun setActive(on: Boolean) {
        if (on == active) return
        active = on
        clearLocked()
        syncHasData()
        version++
    }

    @Synchronized
    fun append(samples: ShortArray) {
        if (samples.isEmpty()) return
        for (s in samples) {
            val v = s / 32768f
            if (v < curMin) curMin = v
            if (v > curMax) curMax = v
            if (++curCount >= binSize) pushBin()
        }
        syncHasData()
        version++
    }

    @Synchronized
    fun clear() {
        clearLocked()
        syncHasData()
        version++
    }

    /** Aktuelle Anzahl Bins im Puffer. */
    val size: Int
        @Synchronized get() = count

    /**
     * Kopiert die letzten [n] Bins (aeltester zuerst) nach [outMin]/[outMax]; Rueckgabe = tatsaechlich
     * kopierte Anzahl (kleiner als [n], falls weniger vorhanden). Die Arrays muessen >= n gross sein.
     */
    @Synchronized
    fun copyLast(n: Int, outMin: FloatArray, outMax: FloatArray): Int {
        val k = minOf(n, count)
        val start = (head - k + maxBins) % maxBins
        for (i in 0 until k) {
            val idx = (start + i) % maxBins
            outMin[i] = mins[idx]
            outMax[i] = maxs[idx]
        }
        return k
    }

    private fun pushBin() {
        mins[head] = curMin
        maxs[head] = curMax
        head = (head + 1) % maxBins
        if (count < maxBins) count++
        curMin = Float.MAX_VALUE
        curMax = -Float.MAX_VALUE
        curCount = 0
    }

    private fun clearLocked() {
        head = 0
        count = 0
        curMin = Float.MAX_VALUE
        curMax = -Float.MAX_VALUE
        curCount = 0
    }

    private fun syncHasData() {
        val v = count > 0
        if (hasData != v) hasData = v
    }
}

/**
 * T193: Verdichtet [n] Bins (alt nach neu, [binMin]/[binMax]) auf [columns] Pixelspalten eines
 * Fensters von [windowBins] Bins. Die Daten sind rechtsbuendig (neueste Bin = rechte Spalte);
 * Spalten ohne Daten bleiben leer (min > max, siehe [columnHasData]).
 */
internal fun columnMinMax(
    binMin: FloatArray, binMax: FloatArray, n: Int, windowBins: Int, columns: Int,
    outMin: FloatArray, outMax: FloatArray
) {
    val offset = windowBins - n  // Bins, die links noch fehlen
    for (c in 0 until columns) {
        outMin[c] = Float.MAX_VALUE
        outMax[c] = -Float.MAX_VALUE
        // Fenster-Bins dieser Spalte: [c*W/cols, (c+1)*W/cols), mindestens 1 Bin
        val a = (c.toLong() * windowBins / columns).toInt()
        val b = maxOf(a + 1, ((c + 1).toLong() * windowBins / columns).toInt())
        for (w in a until b) {
            val i = w - offset
            if (i < 0 || i >= n) continue
            if (binMin[i] < outMin[c]) outMin[c] = binMin[i]
            if (binMax[i] > outMax[c]) outMax[c] = binMax[i]
        }
    }
}

internal fun columnHasData(min: Float, max: Float): Boolean = min <= max

/** Groesste absolute Amplitude ueber alle Spalten mit Daten (0 wenn keine). */
internal fun peakAmplitude(outMin: FloatArray, outMax: FloatArray, columns: Int): Float {
    var p = 0f
    for (c in 0 until columns) {
        if (!columnHasData(outMin[c], outMax[c])) continue
        p = maxOf(p, kotlin.math.abs(outMin[c]), kotlin.math.abs(outMax[c]))
    }
    return p
}

/** Schrittweite der Amplituden-Skala (nur diese Werte, damit die Achse ruhig bleibt). */
private val AMPLITUDE_STEPS = floatArrayOf(0.01f, 0.02f, 0.05f, 0.1f, 0.2f, 0.5f, 1f)

/** T193: Naechsthoeherer Skalenwert fuer die Amplitude [peak] (mindestens 0.01, hoechstens 1). */
internal fun niceAmplitudeRange(peak: Float): Float {
    for (s in AMPLITUDE_STEPS) if (peak <= s) return s
    return 1f
}

/** T193: Beschriftung der Amplitude, z.B. "0.05". */
internal fun amplitudeLabel(a: Float): String =
    if (kotlin.math.abs(a) >= 0.1f) "%.1f".format(java.util.Locale.US, a) else "%.2f".format(java.util.Locale.US, a)
