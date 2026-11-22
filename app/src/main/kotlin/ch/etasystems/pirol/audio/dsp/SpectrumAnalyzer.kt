package ch.etasystems.pirol.audio.dsp

import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * T198: Betragsspektrum in dBFS fuer einen beliebigen Sample-Ausschnitt (rein, ohne Android).
 *
 * Hann-Fenster + Radix-2-FFT ([FFT], [WindowFunction]). Normierung auf die kohaerente
 * Verstaerkung des Fensters: ein Vollaussteuerungs-Sinus (Amplitude 1.0) ergibt 0 dBFS im
 * Peak-Bin, Amplitude 0.1 ergibt -20 dBFS. Samples erwartet im Bereich -1..1.
 *
 * Nicht thread-safe (interne Puffer); eine Instanz pro Thread/Zustand verwenden.
 */
class SpectrumAnalyzer(val fftSize: Int) {
    init {
        require(fftSize >= 2 && (fftSize and (fftSize - 1)) == 0) { "fftSize muss Zweierpotenz sein: $fftSize" }
    }

    /** Anzahl Bins (0 .. Nyquist). */
    val numBins: Int = fftSize / 2 + 1

    private val window = WindowFunction.hann(fftSize)
    private val real = FloatArray(fftSize)
    private val imag = FloatArray(fftSize)
    // Amplitude = 2 * |X| / Summe(w) (einseitiges Spektrum)
    private val scale = 2.0 / window.sumOf { it.toDouble() }

    /**
     * Berechnet das Spektrum der [fftSize] Samples ab [offset] in [out] (Laenge >= [numBins]).
     * Fehlende Samples (Ausschnitt zu kurz) werden mit 0 aufgefuellt.
     */
    fun computeDbfs(samples: FloatArray, offset: Int, out: FloatArray) {
        for (i in 0 until fftSize) {
            val idx = offset + i
            real[i] = if (idx in samples.indices) samples[idx] * window[i] else 0f
            imag[i] = 0f
        }
        FFT.fft(real, imag)
        for (k in 0 until numBins) {
            val mag = sqrt((real[k] * real[k] + imag[k] * imag[k]).toDouble()) * scale
            out[k] = amplitudeToDbfs(mag)
        }
    }

    /** Bequemer Aufruf fuer T200 (Analyse): Spektrum eines Ausschnitts als neues Array. */
    fun computeDbfs(samples: FloatArray, offset: Int = 0): FloatArray =
        FloatArray(numBins).also { computeDbfs(samples, offset, it) }

    companion object {
        /** Waehlbare FFT-Groessen im Live-Spektrum. */
        val FFT_SIZES = intArrayOf(512, 1024, 2048, 4096, 8192)

        /** Untergrenze fuer log(0). */
        const val FLOOR_DBFS = -160f

        fun amplitudeToDbfs(amplitude: Double): Float =
            if (amplitude <= 1e-8) FLOOR_DBFS else (20.0 * log10(amplitude)).toFloat().coerceAtLeast(FLOOR_DBFS)

        /** Mittenfrequenz von Bin [bin] in Hz. */
        fun binToHz(bin: Int, fftSize: Int, sampleRate: Int): Float = bin.toFloat() * sampleRate / fftSize

        /** Naechster Bin zur Frequenz [hz] (begrenzt auf 0..Nyquist). */
        fun hzToBin(hz: Float, fftSize: Int, sampleRate: Int): Int =
            Math.round(hz * fftSize / sampleRate).coerceIn(0, fftSize / 2)
    }
}

/**
 * T198: Exponentielle Mittelung (in dB) und Peak-Hold fuer aufeinanderfolgende Spektren.
 *
 * [tauSec] = 0 heisst keine Mittelung (Anzeige = letzter Frame). Gewicht pro Frame
 * alpha = 1 - exp(-dt / tau), dt = Frame-Abstand in Sekunden. Peak-Hold haelt das Maximum
 * (ohne Abfall) bis [resetPeak].
 */
class SpectrumAverager {
    var avg = FloatArray(0)
        private set
    var peak = FloatArray(0)
        private set

    fun reset() {
        avg = FloatArray(0)
        peak = FloatArray(0)
    }

    fun resetPeak() {
        peak = FloatArray(0)
    }

    fun update(frame: FloatArray, dtSec: Float, tauSec: Float) {
        if (avg.size != frame.size) {
            avg = frame.copyOf()
            peak = frame.copyOf()
            return
        }
        val alpha = if (tauSec <= 0f) 1f else (1.0 - exp(-dtSec.toDouble() / tauSec)).toFloat()
        if (peak.size != frame.size) peak = FloatArray(frame.size) { Float.NEGATIVE_INFINITY }
        for (i in frame.indices) {
            avg[i] += alpha * (frame[i] - avg[i])
            if (avg[i] > peak[i]) peak[i] = avg[i]
        }
    }
}
