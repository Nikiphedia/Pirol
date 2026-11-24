package ch.etasystems.pirol.audio.dsp

import ch.etasystems.pirol.audio.export.FrameSpan
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * T261: Ersetzt in den Abschnitten [sections] das Sprachband durch Rauschen, der Rest des Spektrums bleibt.
 *
 * Verfahren je Sample n im Abschnitt:
 *   band(n) = linearphasiges FIR-Bandpass (Kaiser, [STOP_DB]) um n zentriert (keine Verzoegerung)
 *   y(n)    = x(n) + w(n) * (g * rauschen(n) - band(n))
 * w = weiche Blende (Raised-Cosine, [FADE_SEC]) an beiden Abschnittsraendern, sonst 1; ausserhalb der
 * Abschnitte wird nichts gerechnet, die Samples bleiben bitgenau. x - band(x) ist eine Bandsperre:
 * im Band (300-3900 Hz) >= 50 dB Daempfung, ausserhalb (< 100 Hz, > 4100 Hz) praktisch unveraendert.
 *
 * Rauschen: einmal je Abtastrate erzeugte, bandbegrenzte Rauschtabelle (Zufallsphase, Effektivwert 1),
 * Pegel g je Abschnitt = [NOISE_PERCENTILE]-Perzentil des Band-Effektivwerts in ~20-ms-Frames ueber den
 * Abschnitt plus [NOISE_CONTEXT_SEC] davor/danach (Sprechpausen = Grundrauschen der Umgebung).
 *
 * Band 200-4000 Hz (-6-dB-Punkte) statt 300-3400: Grundton hoher Stimmen und Formant F3 / Konsonanten bis
 * ~4 kHz tragen Verstaendlichkeit; Telefonband allein ist das Minimum fuer verstaendliche Sprache, nicht die
 * Grenze, ab der sie unverstaendlich wird.
 *
 * Streamend: [process] bearbeitet einen beliebigen Block (Session-Frames), Kontext fuer den Filter und die
 * Pegelschaetzung liest [reader] direkt aus der Aufnahme. Reines Kotlin (unit-testbar).
 *
 * @param reader liefert die Frames [from, to) der Session-Achse, ausserhalb [0, totalFrames) mit 0 aufgefuellt
 */
class SpeechBandMasker(
    val sampleRate: Int,
    sections: List<FrameSpan>,
    private val totalFrames: Long,
    private val reader: (from: Long, to: Long) -> ShortArray,
    lowHz: Float = LOW_HZ,
    highHz: Float = HIGH_HZ
) {
    companion object {
        /** -6-dB-Punkt unten; volle Daempfung ab LOW_HZ + TRANSITION_HZ / 2 = 300 Hz */
        const val LOW_HZ = 200f
        /** -6-dB-Punkt oben; volle Daempfung bis HIGH_HZ - TRANSITION_HZ / 2 = 3900 Hz */
        const val HIGH_HZ = 4000f
        const val TRANSITION_HZ = 200f
        const val STOP_DB = 50.0
        const val FADE_SEC = 0.075f
        const val NOISE_FRAME_SEC = 0.02f
        const val NOISE_PERCENTILE = 0.10
        const val NOISE_CONTEXT_SEC = 1.0f
        private const val NOISE_TABLE_SIZE = 1 shl 17
        private const val LEVEL_READ_FRAMES = 1 shl 16

        private val tables = ConcurrentHashMap<String, FloatArray>()
        private val filters = ConcurrentHashMap<String, FloatArray>()

        /** Obere Grenze unter Nyquist (Fremdaudio mit tiefer Abtastrate) */
        internal fun clampHigh(sampleRate: Int, highHz: Float): Float =
            min(highHz, sampleRate * 0.5f - TRANSITION_HZ)

        /** Linearphasiger FIR-Bandpass (Kaiser-Fenster), ungerade Laenge, symmetrisch. */
        fun designBandpass(sampleRate: Int, lowHz: Float, highHz: Float, transitionHz: Float = TRANSITION_HZ, stopDb: Double = STOP_DB): FloatArray =
            filters.getOrPut("$sampleRate|$lowHz|$highHz|$transitionHz|$stopDb") {
                val beta = when {
                    stopDb > 50 -> 0.1102 * (stopDb - 8.7)
                    stopDb >= 21 -> 0.5842 * (stopDb - 21).pow(0.4) + 0.07886 * (stopDb - 21)
                    else -> 0.0
                }
                val dw = 2 * PI * transitionHz / sampleRate
                var n = ceil((stopDb - 8) / (2.285 * dw)).toInt() + 1
                if (n % 2 == 0) n++
                val m = (n - 1) / 2
                val fl = lowHz.toDouble() / sampleRate
                val fh = highHz.toDouble() / sampleRate
                val i0b = besselI0(beta)
                FloatArray(n) { i ->
                    val k = (i - m).toDouble()
                    val ideal = if (k == 0.0) 2 * (fh - fl) else (sin(2 * PI * fh * k) - sin(2 * PI * fl * k)) / (PI * k)
                    val r = k / m
                    val win = besselI0(beta * sqrt(max(0.0, 1 - r * r))) / i0b
                    (ideal * win).toFloat()
                }
            }

        private fun besselI0(x: Double): Double {
            var sum = 1.0
            var term = 1.0
            val q = x * x / 4
            var k = 1
            while (k < 60) {
                term *= q / (k.toDouble() * k)
                sum += term
                if (term < sum * 1e-12) break
                k++
            }
            return sum
        }

        /**
         * Bandbegrenzte Rauschtabelle (periodisch, Laenge [NOISE_TABLE_SIZE], Effektivwert 1): flach im Band,
         * Raised-Cosine-Flanken ueber die Uebergaenge des Filters, ausserhalb 0. Fester Startwert (reproduzierbar).
         */
        fun noiseTable(sampleRate: Int, lowHz: Float, highHz: Float): FloatArray =
            tables.getOrPut("$sampleRate|$lowHz|$highHz") {
                val n = NOISE_TABLE_SIZE
                val re = FloatArray(n)
                val im = FloatArray(n)
                val rnd = java.util.Random(261L)
                val half = TRANSITION_HZ / 2
                for (k in 1 until n / 2) {
                    val f = k.toFloat() * sampleRate / n
                    val mag = when {
                        f < lowHz - half || f > highHz + half -> 0f
                        f < lowHz + half -> (0.5 - 0.5 * cos(PI * (f - (lowHz - half)) / TRANSITION_HZ)).toFloat()
                        f > highHz - half -> (0.5 - 0.5 * cos(PI * ((highHz + half) - f) / TRANSITION_HZ)).toFloat()
                        else -> 1f
                    }
                    if (mag == 0f) continue
                    val ph = rnd.nextDouble() * 2 * PI
                    re[k] = (mag * cos(ph)).toFloat(); im[k] = (mag * sin(ph)).toFloat()
                    re[n - k] = re[k]; im[n - k] = -im[k]
                }
                // Inverse FFT ueber die konjugierte Vorwaertstransformation
                for (i in 0 until n) im[i] = -im[i]
                FFT.fft(re, im)
                var sq = 0.0
                for (i in 0 until n) sq += re[i].toDouble() * re[i]
                val rms = sqrt(sq / n)
                val scale = if (rms > 0) (1.0 / rms).toFloat() else 0f
                FloatArray(n) { re[it] * scale }
            }
    }

    private val sections: List<FrameSpan> = sections.filter { it.length > 0 }.sortedBy { it.startFrame }
    private val high = clampHigh(sampleRate, highHz)
    private val low = min(lowHz, high / 2)
    private val h = designBandpass(sampleRate, low, high)
    private val half = h.size / 2
    private val noise = noiseTable(sampleRate, low, high)
    private val fade = max(1, (FADE_SEC * sampleRate).toInt())
    private val levels = DoubleArray(this.sections.size) { Double.NaN }

    /** Filterlaenge (Taps), fuer Log/Handover */
    val taps: Int get() = h.size

    /** true, wenn der Block [from, from+len) einen Abschnitt schneidet. */
    fun affects(from: Long, len: Int): Boolean =
        sections.any { it.startFrame < from + len && it.endFrame > from }

    /**
     * Bearbeitet [block] (Frames ab Session-Frame [from], [len] gueltige Samples) an Ort und Stelle.
     * Ausserhalb der Abschnitte bleiben die Samples unveraendert.
     */
    fun process(from: Long, block: ShortArray, len: Int = block.size) {
        val end = from + len
        for ((idx, s) in sections.withIndex()) {
            if (s.endFrame <= from) continue
            if (s.startFrame >= end) break
            val a = max(s.startFrame, from)
            val b = min(s.endFrame, end)
            val g = levelFor(idx).toFloat()
            val ctx = toFloat(reader(a - half, b + half))
            val count = (b - a).toInt()
            val secLen = s.length
            val f = min(fade.toLong(), secLen / 2).coerceAtLeast(1L).toFloat()
            val center = h[half]
            for (i in 0 until count) {
                val n = a + i
                // Symmetrischer FIR: band = h[M] x[n] + sum h[k] (x[n-M+k] + x[n+M-k])
                var acc = center * ctx[i + half]
                var k = 0
                val top = i + 2 * half
                while (k < half) {
                    acc += h[k] * (ctx[i + k] + ctx[top - k])
                    k++
                }
                val d = min(n - s.startFrame, s.endFrame - 1 - n).toFloat()
                val t = d / f
                val w = if (t >= 1f) 1f else (0.5 - 0.5 * cos(PI * t)).toFloat()
                if (w <= 0f) continue
                val pos = (n - from).toInt()
                val x = block[pos].toFloat()
                val nz = g * noise[(n % noise.size).toInt()]
                val y = x + w * (nz - acc)
                block[pos] = Math.round(y).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
        }
    }

    /** Rauschpegel (Effektivwert, Sample-Einheiten) eines Abschnitts; einmal berechnet. */
    private fun levelFor(idx: Int): Double {
        val cached = levels[idx]
        if (!cached.isNaN()) return cached
        val s = sections[idx]
        val ctx = (NOISE_CONTEXT_SEC * sampleRate).toLong()
        val from = max(0L, s.startFrame - ctx)
        val to = min(totalFrames, s.endFrame + ctx)
        val level = bandLevel(from, to)
        levels[idx] = level
        return level
    }

    /** [NOISE_PERCENTILE]-Perzentil des Band-Effektivwerts in Frames (Hann, FFT) ueber [from, to). */
    private fun bandLevel(from: Long, to: Long): Double {
        var size = 64
        while (size < NOISE_FRAME_SEC * sampleRate) size *= 2
        val win = WindowFunction.hann(size)
        var winSq = 0.0
        for (v in win) winSq += v.toDouble() * v
        val kLo = max(1, ceil((low + TRANSITION_HZ / 2) * size / sampleRate.toDouble()).toInt())
        val kHi = min(size / 2 - 1, ((high - TRANSITION_HZ / 2) * size / sampleRate.toDouble()).toInt())
        val values = ArrayList<Double>()
        val re = FloatArray(size)
        val im = FloatArray(size)
        var pos = from
        while (pos + size <= to || (pos == from && to > from)) {
            val chunkEnd = min(to, pos + LEVEL_READ_FRAMES.toLong())
            val frames = max(size.toLong(), chunkEnd - pos)
            val pcm = reader(pos, pos + frames)
            var off = 0
            while (off + size <= pcm.size) {
                for (i in 0 until size) { re[i] = pcm[off + i] * win[i]; im[i] = 0f }
                FFT.fft(re, im)
                var p = 0.0
                for (k in kLo..kHi) p += re[k].toDouble() * re[k] + im[k].toDouble() * im[k]
                // Einseitiges Leistungsspektrum, Fenster-normiert (Parseval)
                values += sqrt(2 * p / (size * winSq))
                off += size
            }
            if (off == 0) break
            pos += off
        }
        if (values.isEmpty()) return 0.0
        values.sort()
        return values[((values.size - 1) * NOISE_PERCENTILE).toInt()]
    }

    private fun toFloat(pcm: ShortArray): FloatArray = FloatArray(pcm.size) { pcm[it].toFloat() }
}
