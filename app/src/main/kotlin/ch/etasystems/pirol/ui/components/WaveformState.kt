package ch.etasystems.pirol.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ch.etasystems.pirol.audio.dsp.BandpassFilter
import kotlin.math.abs

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
    private val sumSqs = FloatArray(maxBins)    // T200: Quadratsumme (gefiltert) pro Bin
    private val rawPeaks = FloatArray(maxBins)  // T200: Spitzenbetrag Rohsignal pro Bin
    private var head = 0
    private var count = 0
    private var curMin = Float.MAX_VALUE
    private var curMax = -Float.MAX_VALUE
    private var curSumSq = 0f
    private var curRaw = 0f
    private var curCount = 0

    // T200: Bandpass (gemeinsamer Frequenzbereich), neu gebaut bei Aenderung von Band oder Rate
    private var bandLo = 0f
    private var bandHi = Float.MAX_VALUE
    private var filter: BandpassFilter? = null
    private var filterRate = 0
    private var filterDirty = true

    /** true = Oszillogramm wird angezeigt, der Collector soll [append] aufrufen. */
    @Volatile var active: Boolean = false
        private set

    /** T200: Freeze: neue Samples werden ignoriert, das Bild bleibt stehen (nicht persistent). */
    var frozen by mutableStateOf(false)

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
        frozen = false
        clearLocked()
        syncHasData()
        version++
    }

    /** T200: Durchlassbereich in Hz; wirkt ab den naechsten Samples (voller Bereich = ungefiltert). */
    @Synchronized
    fun setBand(lowHz: Float, highHz: Float) {
        if (lowHz == bandLo && highHz == bandHi) return
        bandLo = lowHz
        bandHi = highHz
        filterDirty = true
    }

    @Synchronized
    fun append(samples: ShortArray, sampleRate: Int = 48000) {
        if (samples.isEmpty() || frozen) return
        if (filterDirty || sampleRate != filterRate) {
            filter = BandpassFilter(sampleRate, bandLo, bandHi).takeUnless { it.isPassThrough }
            filterRate = sampleRate
            filterDirty = false
        }
        val f = filter
        for (s in samples) {
            val raw = s / 32768f
            val v = f?.process(raw) ?: raw
            if (v < curMin) curMin = v
            if (v > curMax) curMax = v
            curSumSq += v * v
            val a = abs(raw)
            if (a > curRaw) curRaw = a
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
     * T200: optional Quadratsumme (gefiltert) und Roh-Spitzenbetrag pro Bin.
     */
    @Synchronized
    fun copyLast(
        n: Int, outMin: FloatArray, outMax: FloatArray,
        outSumSq: FloatArray? = null, outRawPeak: FloatArray? = null
    ): Int {
        val k = minOf(n, count)
        val start = (head - k + maxBins) % maxBins
        for (i in 0 until k) {
            val idx = (start + i) % maxBins
            outMin[i] = mins[idx]
            outMax[i] = maxs[idx]
            if (outSumSq != null) outSumSq[i] = sumSqs[idx]
            if (outRawPeak != null) outRawPeak[i] = rawPeaks[idx]
        }
        return k
    }

    private fun pushBin() {
        mins[head] = curMin
        maxs[head] = curMax
        sumSqs[head] = curSumSq
        rawPeaks[head] = curRaw
        head = (head + 1) % maxBins
        if (count < maxBins) count++
        resetCurrent()
    }

    private fun resetCurrent() {
        curMin = Float.MAX_VALUE
        curMax = -Float.MAX_VALUE
        curSumSq = 0f
        curRaw = 0f
        curCount = 0
    }

    private fun clearLocked() {
        head = 0
        count = 0
        resetCurrent()
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
internal fun amplitudeLabel(a: Float): String = when {
    kotlin.math.abs(a) >= 0.1f -> "%.1f".format(java.util.Locale.US, a)
    kotlin.math.abs(a) >= 0.01f || a == 0f -> "%.2f".format(java.util.Locale.US, a)
    else -> "%.4f".format(java.util.Locale.US, a)  // T200: Maximalpegel unter -40 dBFS
}
