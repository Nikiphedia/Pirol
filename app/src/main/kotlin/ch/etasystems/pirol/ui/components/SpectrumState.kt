package ch.etasystems.pirol.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ch.etasystems.pirol.audio.dsp.SpectrumAnalyzer
import ch.etasystems.pirol.audio.dsp.SpectrumAverager
import ch.etasystems.pirol.audio.dsp.SpectrumTraceBuffer

/**
 * T198: Zustand des Live-Spektrums aus Rohsamples (analog [WaveformState]).
 *
 * Der Collector im LiveViewModel ruft [append] nur, solange [active] true ist (Spektrum-Modus
 * sichtbar). Pro [hopSamples] neuen Samples wird eine FFT ueber die letzten fftSize Samples
 * gerechnet (im Collector-Thread, nicht in der Draw-Phase), gemittelt und mit Peak-Hold versehen.
 * Die Draw-Phase holt sich per [copyInto] eine Kopie.
 *
 * Thread-safe ueber @Synchronized (Schreiber: Collector-Job, Leser: Draw-Phase / UI).
 */
class SpectrumState {
    private val history = FloatArray(MAX_FFT)
    private var head = 0
    private var filled = 0
    private var sinceLast = 0

    private var analyzer = SpectrumAnalyzer(2048)
    private var frame = FloatArray(analyzer.numBins)
    private var window = FloatArray(analyzer.fftSize)
    private val averager = SpectrumAverager()
    private var tauSec = SpectrumAveraging.SHORT.tauSec
    private val traces = SpectrumTraceBuffer()  // T201: rollende Peak-Spuren

    /** Abtastrate der zuletzt gelieferten Samples. */
    @Volatile var sampleRate: Int = 48000
        private set

    @Volatile var active: Boolean = false
        private set

    /** Freeze: neue Samples werden ignoriert, das Bild bleibt stehen (nicht persistent). */
    var frozen by mutableStateOf(false)

    /** Draw-Dependency: aendert sich bei jedem neuen Frame/Reset. */
    var version by mutableIntStateOf(0)
        private set

    /** true, sobald mindestens ein Spektrum berechnet ist. */
    var hasData by mutableStateOf(false)
        private set

    val fftSize: Int
        @Synchronized get() = analyzer.fftSize

    @Synchronized
    fun setActive(on: Boolean) {
        if (on == active) return
        active = on
        resetLocked()
    }

    /** FFT-Groesse und Mittelung setzen; neue FFT-Groesse verwirft den Verlauf. */
    @Synchronized
    fun configure(fftSize: Int, tauSec: Float) {
        this.tauSec = tauSec
        if (fftSize != analyzer.fftSize && fftSize in SpectrumAnalyzer.FFT_SIZES) {
            analyzer = SpectrumAnalyzer(fftSize)
            frame = FloatArray(analyzer.numBins)
            window = FloatArray(fftSize)
            averager.reset()
            traces.clear()
            sinceLast = 0
            hasData = false
            version++
        }
    }

    /** T201: Dauer der Peak-Spuren in Sekunden (0 = aus, leert die Spuren). */
    @Synchronized
    fun setTraceSeconds(sec: Float) {
        traces.setDuration(sec)
        version++
    }

    /** T201: Spuren-Schichten (juengste zuerst), leer wenn aus. */
    @Synchronized
    fun traceLayers(): List<SpectrumTraceBuffer.Layer> = traces.layers()

    @Synchronized
    fun resetPeak() {
        averager.resetPeak()
        version++
    }

    @Synchronized
    fun clear() = resetLocked()

    @Synchronized
    fun append(samples: ShortArray, sampleRate: Int) {
        if (samples.isEmpty() || frozen) return
        if (sampleRate != this.sampleRate) {
            this.sampleRate = sampleRate
            averager.reset()
            traces.clear()
        }
        for (s in samples) {
            history[head] = s / 32768f
            head = (head + 1) % MAX_FFT
            if (filled < MAX_FFT) filled++
        }
        sinceLast += samples.size
        val n = analyzer.fftSize
        // Hoechstens ~50 Spektren/s (kleine FFTs ueberlappen sonst unnoetig stark)
        val hop = maxOf(n / 2, sampleRate / 50)
        if (filled < n || sinceLast < hop) return
        val dtSec = sinceLast.toFloat() / sampleRate
        sinceLast = 0
        val start = (head - n + MAX_FFT) % MAX_FFT
        for (i in 0 until n) window[i] = history[(start + i) % MAX_FFT]
        analyzer.computeDbfs(window, 0, frame)
        averager.update(frame, dtSec, tauSec)
        traces.add(averager.avg, dtSec)
        if (!hasData) hasData = true
        version++
    }

    /**
     * Kopiert Mittel- und Peak-Spektrum in [outAvg]/[outPeak] (werden bei Bedarf neu angelegt).
     * Rueckgabe: Paar der (ggf. neuen) Arrays, null wenn noch keine Daten.
     */
    @Synchronized
    fun copyInto(outAvg: FloatArray?, outPeak: FloatArray?): Pair<FloatArray, FloatArray>? {
        val a = averager.avg
        val p = averager.peak
        if (a.isEmpty()) return null
        val ra = if (outAvg != null && outAvg.size == a.size) outAvg else FloatArray(a.size)
        val rp = if (outPeak != null && outPeak.size == a.size) outPeak else FloatArray(a.size)
        a.copyInto(ra)
        if (p.size == a.size) p.copyInto(rp) else a.copyInto(rp)
        return ra to rp
    }

    private fun resetLocked() {
        head = 0
        filled = 0
        sinceLast = 0
        averager.reset()
        traces.clear()
        hasData = false
        frozen = false
        version++
    }

    companion object {
        const val MAX_FFT = 8192
    }
}
