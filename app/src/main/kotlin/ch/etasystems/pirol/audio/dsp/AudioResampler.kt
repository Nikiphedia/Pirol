package ch.etasystems.pirol.audio.dsp

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * T133: Zwei Verfahren — LINEAR (Legacy, ohne Anti-Alias) und POLYPHASE (Kaiser-FIR, Default
 * ueber [defaultMode]). Die Overloads ohne Mode-Parameter bleiben LINEAR (unveraendert).
 *
 * Linearer Interpolations-Resampler.
 * Portiert von AMSEL Desktop — genuegt fuer BirdNET-Input-Qualitaet.
 *
 * Unterstuetzte Konvertierungen:
 * - 48 kHz → 32 kHz (Standard: Oboe-Aufnahme → BirdNET)
 * - 96 kHz → 32 kHz (Fledermaus-Modus → BirdNET)
 * - Beliebige Raten (lineares Resampling)
 */
object AudioResampler {

    /** T133: POLYPHASE = Kaiser-FIR mit Anti-Alias (Default), LINEAR = altes Verfahren. */
    enum class Mode {
        POLYPHASE, LINEAR;

        companion object {
            fun parse(s: String?): Mode = if (s == "LINEAR") LINEAR else POLYPHASE
        }
    }

    /** Globaler Default fuer Aufrufer ohne explizite Moduswahl (App-Start / Setting-Wechsel). */
    @Volatile
    var defaultMode: Mode = Mode.POLYPHASE

    /** Stopband-Daempfung des Filterdesigns (dB) */
    private const val STOPBAND_DB = 70.0

    /** Vorberechnetes Polyphasen-Filter fuer ein Raten-Paar. */
    class Filter internal constructor(
        val up: Int,
        val down: Int,
        /** Anzahl FIR-Taps des Prototyp-Filters (bei L-facher Rate) */
        val taps: Int,
        internal val delay: Int,
        /** Polyphasen-Zweige: branches[p][t] = h[p + t*L] */
        internal val branches: Array<FloatArray>
    )

    private val filterCache = ConcurrentHashMap<Long, Filter>()

    private fun gcd(a: Int, b: Int): Int {
        var x = a
        var y = b
        while (y != 0) { val t = x % y; x = y; y = t }
        return x
    }

    private fun bessel0(x: Double): Double {
        var sum = 1.0
        var term = 1.0
        var k = 1
        while (term > 1e-12 * sum) { val h = x / (2.0 * k); term *= h * h; sum += term; k++ }
        return sum
    }

    /** Liefert (und cached) das Filter fuer fromRate nach toRate. */
    fun filterFor(fromRate: Int, toRate: Int): Filter {
        val key = (fromRate.toLong() shl 32) or toRate.toLong()
        return filterCache.getOrPut(key) { design(fromRate, toRate) }
    }

    /** Anzahl Taps fuers Logging (0 bei gleicher Rate). */
    fun tapsFor(fromRate: Int, toRate: Int): Int =
        if (fromRate == toRate) 0 else filterFor(fromRate, toRate).taps

    private fun design(fromRate: Int, toRate: Int): Filter {
        val g = gcd(fromRate, toRate)
        val up = toRate / g
        val down = fromRate / g
        val fsUp = fromRate.toDouble() * up
        val lowRate = minOf(fromRate, toRate).toDouble()
        // -6 dB bei 0.45 * kleinere Rate, Stopband ab 0.5 * kleinere Rate (neues Nyquist)
        val fc = 0.45 * lowRate / fsUp
        val transition = 0.1 * lowRate / fsUp
        val beta = 0.1102 * (STOPBAND_DB - 8.7)
        var n = Math.ceil((STOPBAND_DB - 7.95) / (2.285 * 2.0 * PI * transition)).toInt() + 1
        if (n % 2 == 0) n++
        val mid = (n - 1) / 2
        val i0b = bessel0(beta)
        val h = DoubleArray(n)
        for (i in 0 until n) {
            val m = (i - mid).toDouble()
            val sinc = if (i == mid) 2.0 * fc else sin(2.0 * PI * fc * m) / (PI * m)
            val r = m / mid
            val w = bessel0(beta * sqrt((1.0 - r * r).coerceAtLeast(0.0))) / i0b
            h[i] = sinc * w * up // Gain L gleicht die Nullen-Einfuegung aus
        }
        val perBranch = (n + up - 1) / up
        val branches = Array(up) { p ->
            FloatArray(perBranch) { t -> val idx = p + t * up; if (idx < n) h[idx].toFloat() else 0f }
        }
        return Filter(up, down, n, mid, branches)
    }

    /**
     * Polyphasen-Resampling (Kaiser-FIR). Gruppenlaufzeit kompensiert: Ausgangs-Sample 0 entspricht
     * Eingangs-Sample 0. Zustandslos, Raender mit Nullen aufgefuellt.
     */
    fun resamplePolyphase(input: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        require(fromRate > 0 && toRate > 0) { "Sampleraten muessen positiv sein" }
        if (fromRate == toRate) return input.copyOf()
        if (input.isEmpty()) return FloatArray(0)
        val f = filterFor(fromRate, toRate)
        val up = f.up
        val down = f.down
        val size = input.size
        val outLen = ((size.toLong() * up) / down).toInt()
        val out = FloatArray(outLen)
        for (m in 0 until outLen) {
            val j = m.toLong() * down + f.delay
            val p = (j % up).toInt()
            val i0 = ((j - p) / up).toInt()
            val br = f.branches[p]
            val t = br.size
            var acc = 0f
            if (i0 < size && i0 - (t - 1) >= 0) {
                for (k in 0 until t) acc += br[k] * input[i0 - k]
            } else {
                for (k in 0 until t) {
                    val idx = i0 - k
                    if (idx in 0 until size) acc += br[k] * input[idx]
                }
            }
            out[m] = acc
        }
        return out
    }

    /** Resampling mit explizitem Modus. */
    fun resample(input: FloatArray, fromRate: Int, toRate: Int, mode: Mode): FloatArray =
        if (mode == Mode.LINEAR) resample(input, fromRate, toRate) else resamplePolyphase(input, fromRate, toRate)

    /** ShortArray-Variante (normalisiert auf [-1,1]) mit explizitem Modus. */
    fun resample(input: ShortArray, fromRate: Int, toRate: Int, mode: Mode): FloatArray {
        if (mode == Mode.LINEAR || fromRate == toRate) return resample(input, fromRate, toRate)
        if (input.isEmpty()) return FloatArray(0)
        val scale = 1f / 32768f
        return resamplePolyphase(FloatArray(input.size) { input[it] * scale }, fromRate, toRate)
    }

    /**
     * Resampelt Float-Audio auf die Ziel-Samplerate.
     * Lineare Interpolation zwischen benachbarten Samples.
     *
     * @param input Normalisierte Samples [-1.0, 1.0]
     * @param fromRate Quell-Samplerate in Hz
     * @param toRate Ziel-Samplerate in Hz
     * @return Resampeltes FloatArray
     */
    fun resample(input: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        if (fromRate == toRate) return input.copyOf()
        require(fromRate > 0 && toRate > 0) { "Sampleraten muessen positiv sein" }
        if (input.isEmpty()) return FloatArray(0)

        val ratio = fromRate.toDouble() / toRate.toDouble()
        val outputLength = (input.size / ratio).toInt()
        val output = FloatArray(outputLength)

        for (i in 0 until outputLength) {
            val srcPos = i * ratio
            val srcIdx = floor(srcPos).toInt()
            val frac = (srcPos - srcIdx).toFloat()

            output[i] = if (srcIdx + 1 < input.size) {
                input[srcIdx] * (1f - frac) + input[srcIdx + 1] * frac
            } else {
                input[srcIdx.coerceAtMost(input.size - 1)]
            }
        }

        return output
    }

    /**
     * Convenience-Overload: ShortArray → Float-Normalisierung + Resampling.
     * Kombiniert die Konvertierung von 16-bit PCM zu normalisierten Floats
     * mit dem Resampling in einem Schritt.
     *
     * @param input 16-bit PCM Samples
     * @param fromRate Quell-Samplerate in Hz
     * @param toRate Ziel-Samplerate in Hz
     * @return Resampeltes FloatArray, normalisiert auf [-1.0, 1.0]
     */
    fun resample(input: ShortArray, fromRate: Int, toRate: Int): FloatArray {
        require(fromRate > 0 && toRate > 0) { "Sampleraten muessen positiv sein" }
        if (input.isEmpty()) return FloatArray(0)

        // Normalisierung + Resampling in einem Durchgang (kein Zwischen-FloatArray).
        // Skalierung mit 1/32768 (Zweierpotenz) ist exakt → bitidentisch zur
        // frueheren Variante (erst normalisieren, dann interpolieren).
        val scale = 1f / 32768f
        if (fromRate == toRate) {
            return FloatArray(input.size) { input[it] * scale }
        }

        val ratio = fromRate.toDouble() / toRate.toDouble()
        val outputLength = (input.size / ratio).toInt()
        val output = FloatArray(outputLength)

        for (i in 0 until outputLength) {
            val srcPos = i * ratio
            val srcIdx = floor(srcPos).toInt()
            val frac = (srcPos - srcIdx).toFloat()

            output[i] = if (srcIdx + 1 < input.size) {
                (input[srcIdx] * scale) * (1f - frac) + (input[srcIdx + 1] * scale) * frac
            } else {
                input[srcIdx.coerceAtMost(input.size - 1)] * scale
            }
        }

        return output
    }
}
