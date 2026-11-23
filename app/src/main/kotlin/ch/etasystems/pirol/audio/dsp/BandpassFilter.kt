package ch.etasystems.pirol.audio.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * T200: Biquad (Direktform I) nach RBJ-Audio-EQ-Cookbook, Butterworth-Guete (Q = 1/sqrt 2).
 * Nur fuer die Anzeige (Oszillogramm), nicht fuer Aufnahme oder Inferenz.
 */
class Biquad private constructor(
    private val b0: Float, private val b1: Float, private val b2: Float,
    private val a1: Float, private val a2: Float
) {
    private var x1 = 0f
    private var x2 = 0f
    private var y1 = 0f
    private var y2 = 0f

    fun process(x: Float): Float {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = x
        y2 = y1; y1 = y
        return y
    }

    companion object {
        private const val Q = 0.70710678

        /** Hochpass 2. Ordnung mit Grenzfrequenz [fc] (-3 dB). */
        fun highPass(fc: Float, sampleRate: Int): Biquad = make(fc, sampleRate, high = true)

        /** Tiefpass 2. Ordnung mit Grenzfrequenz [fc] (-3 dB). */
        fun lowPass(fc: Float, sampleRate: Int): Biquad = make(fc, sampleRate, high = false)

        private fun make(fc: Float, sampleRate: Int, high: Boolean): Biquad {
            val w0 = 2.0 * PI * fc / sampleRate
            val c = cos(w0)
            val alpha = sin(w0) / (2.0 * Q)
            val a0 = 1.0 + alpha
            val b0 = if (high) (1.0 + c) / 2.0 else (1.0 - c) / 2.0
            val b1 = if (high) -(1.0 + c) else 1.0 - c
            return Biquad(
                (b0 / a0).toFloat(), (b1 / a0).toFloat(), (b0 / a0).toFloat(),
                (-2.0 * c / a0).toFloat(), ((1.0 - alpha) / a0).toFloat()
            )
        }
    }
}

/**
 * T200: Bandpass fuer die Oszillogramm-Anzeige: Hochpass bei [lowHz] und Tiefpass bei [highHz]
 * (je 2. Ordnung, 12 dB/Oktave). Ein Teil entfaellt, wenn er nichts bewirkt: [lowHz] unter
 * [MIN_HIGHPASS_HZ] = kein Hochpass, [highHz] ab 98 % von Nyquist = kein Tiefpass.
 * Voller Bereich = ungefiltert ([isPassThrough]).
 */
class BandpassFilter(sampleRate: Int, lowHz: Float, highHz: Float) {
    private val nyquist = sampleRate / 2f
    private val hp: Biquad? = if (lowHz >= MIN_HIGHPASS_HZ && lowHz < nyquist * 0.98f) Biquad.highPass(lowHz, sampleRate) else null
    private val lp: Biquad? = if (highHz > 0f && highHz < nyquist * 0.98f) Biquad.lowPass(highHz, sampleRate) else null

    val isPassThrough: Boolean get() = hp == null && lp == null

    fun process(x: Float): Float {
        var y = x
        if (hp != null) y = hp.process(y)
        if (lp != null) y = lp.process(y)
        return y
    }

    companion object {
        /** Darunter kein Hochpass (Gleichanteil/Infraschall bleibt, wie ungefiltert). */
        const val MIN_HIGHPASS_HZ = 10f
    }
}

/** T200: Untergrenze fuer dBFS-Werte (gegen log(0)), wie der Sonogramm-Boden. */
const val LEVEL_FLOOR_DBFS = MEL_FLOOR_DBFS

/** T200: Ab diesem Betrag (Rohsignal, -1..1) gilt ein Sample als uebersteuert. */
const val CLIP_THRESHOLD = 0.99f

/** T200: Amplitude (linear, Vollaussteuerung = 1) in dBFS; 0 -> [LEVEL_FLOOR_DBFS]. */
fun amplitudeToDbfs(a: Float): Float =
    max(20f * log10(max(kotlin.math.abs(a), 1e-12f)), LEVEL_FLOOR_DBFS)

/** T200: RMS in dBFS aus Quadratsumme [sumSq] ueber [count] Samples (Sinus Amplitude 1 = -3.01 dBFS). */
fun rmsDbfs(sumSq: Double, count: Long): Float =
    if (count <= 0L) LEVEL_FLOOR_DBFS else amplitudeToDbfs(sqrt(sumSq / count).toFloat())

/** T200: Uebersteuerung im Rohsignal (Spitzenbetrag >= [CLIP_THRESHOLD]). */
fun isClipping(rawPeak: Float): Boolean = rawPeak >= CLIP_THRESHOLD
