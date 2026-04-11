package ch.etasystems.pirol.audio.dsp

import kotlin.math.abs

/**
 * DSP-Utilities fuer die Inference-Pipeline.
 *
 * Alle Funktionen arbeiten IN-PLACE auf FloatArray [-1, 1].
 * Werden VOR classify() auf die Inference-Kopie angewandt —
 * das Original-Audio bleibt unangetastet.
 */
object AudioDspUtils {

    /**
     * Peak-Normalisierung: Skaliert Samples so dass Peak bei targetPeak liegt.
     * Nur Verstaerkung (gain > 1), nie Daempfung — bereits lautes Audio bleibt unveraendert.
     */
    fun peakNormalize(samples: FloatArray, targetPeak: Float = 0.95f) {
        var maxAbs = 0f
        for (s in samples) { if (abs(s) > maxAbs) maxAbs = abs(s) }
        if (maxAbs < 1e-6f) return  // Stille — nicht normalisieren
        val gain = targetPeak / maxAbs
        if (gain > 1f) {
            for (i in samples.indices) { samples[i] *= gain }
        }
    }

    /**
     * Brickwall-Limiter: Hard-Clip auf [-limit, limit].
     * Sicherheitsnetz nach Normalisierung um Clipping zu verhindern.
     */
    fun brickwallLimit(samples: FloatArray, limit: Float = 1.0f) {
        for (i in samples.indices) {
            samples[i] = samples[i].coerceIn(-limit, limit)
        }
    }

    /**
     * 2. Ordnung Butterworth Hochpassfilter (IIR).
     * Direct Form II Transposed.
     * Filtert Wind-Rumpeln und Trittschall unterhalb cutoffHz.
     */
    fun highpassFilter(samples: FloatArray, sampleRate: Int, cutoffHz: Float = 200f) {
        val w0 = 2.0 * Math.PI * cutoffHz / sampleRate
        val alpha = Math.sin(w0) / (2.0 * 0.7071) // Q = sqrt(2)/2 fuer Butterworth
        val cosW0 = Math.cos(w0)

        val b0 = ((1 + cosW0) / 2).toFloat()
        val b1 = (-(1 + cosW0)).toFloat()
        val b2 = ((1 + cosW0) / 2).toFloat()
        val a0 = (1 + alpha).toFloat()
        val a1 = (-2 * cosW0).toFloat()
        val a2 = (1 - alpha).toFloat()

        // Normalisieren
        val nb0 = b0 / a0; val nb1 = b1 / a0; val nb2 = b2 / a0
        val na1 = a1 / a0; val na2 = a2 / a0

        // Direct Form II Transposed
        var z1 = 0f; var z2 = 0f
        for (i in samples.indices) {
            val input = samples[i]
            val output = nb0 * input + z1
            z1 = nb1 * input - na1 * output + z2
            z2 = nb2 * input - na2 * output
            samples[i] = output
        }
    }
}
