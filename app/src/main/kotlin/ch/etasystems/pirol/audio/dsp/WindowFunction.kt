package ch.etasystems.pirol.audio.dsp

import kotlin.math.PI
import kotlin.math.cos

/**
 * Fensterfunktionen fuer FFT-Vorverarbeitung.
 * Fenster werden einmal berechnet und gecached.
 */
object WindowFunction {

    private val cache = java.util.concurrent.ConcurrentHashMap<Int, FloatArray>()

    /**
     * Erzeugt ein Hann-Fenster der gegebenen Groesse.
     * Ergebnis wird intern gecached.
     *
     * w[n] = 0.5 * (1 - cos(2*PI*n / (N-1)))
     */
    fun hann(size: Int): FloatArray {
        return cache.getOrPut(size) {
            FloatArray(size) { n ->
                (0.5 * (1.0 - cos(2.0 * PI * n / (size - 1)))).toFloat()
            }
        }
    }

    /**
     * Multipliziert samples in-place mit dem Fenster.
     * samples und window muessen gleiche Laenge haben.
     */
    fun applyWindow(samples: FloatArray, window: FloatArray) {
        for (i in samples.indices) {
            samples[i] *= window[i]
        }
    }
}
