package ch.etasystems.pirol.audio

import ch.etasystems.pirol.audio.dsp.AudioResampler
import ch.etasystems.pirol.audio.dsp.SpectrogramConfig
import kotlin.math.roundToInt

/**
 * T294: Wiedergabe von Sessions ueber 96 kHz (Ultraschall, Import bis 500 kHz).
 *
 * AudioTrack schafft meist hoechstens 192 kHz, und Ultraschall ist unhoerbar. Darum zwei Arten:
 * - Zeitdehnung (Default Faktor 10): die Samples laufen unveraendert, aber mit Rate / Faktor. 40 kHz wird
 *   zu 4 kHz, eine Sekunde dauert zehn Sekunden.
 * - Normal (Faktor 1): auf 48 kHz heruntergerechnet (Polyphasen-FIR), Echtzeit, nur der hoerbare Teil.
 *
 * Die Kopfposition des AudioTrack wird ueber [Plan.positionRate] in Session-Sekunden umgerechnet: bei Zeitdehnung
 * zaehlt sie Quell-Frames (Session-Rate), bei Normal Ausgangs-Frames (48 kHz). Reine Logik (unit-testbar).
 */
object HighRatePlayback {
    const val DEFAULT_EXPANSION = 10

    /** Wahl im Umschalter: 1 = normal (heruntergerechnet), sonst Zeitdehnungsfaktor */
    val EXPANSION_OPTIONS = listOf(1, 10, 20)

    const val NORMAL_OUT_RATE = 48_000
    const val MIN_TRACK_RATE = 4_000
    const val MAX_TRACK_RATE = 192_000

    /** Gilt die Hochraten-Wiedergabe fuer diese Rate? (wie die Ultraschall-Anzeige: > 96 kHz) */
    fun applies(sampleRate: Int): Boolean = SpectrogramConfig.isUltrasoundRate(sampleRate)

    /** Ungueltige/alte Pref-Werte auf den Default */
    fun sanitize(expansion: Int): Int = if (expansion in 1..100) expansion else DEFAULT_EXPANSION

    /**
     * @param expansion tatsaechlicher Faktor (1 = normal)
     * @param outRate Rate des AudioTrack
     * @param resampleTo Zielrate beim Herunterrechnen, null = Samples unveraendert (Zeitdehnung)
     */
    data class Plan(val sourceRate: Int, val expansion: Int, val outRate: Int, val resampleTo: Int?) {
        /** Rate, mit der die Kopfposition (Frames) in Session-Sekunden umgerechnet wird */
        val positionRate: Int get() = resampleTo ?: sourceRate
        val timeExpanded: Boolean get() = resampleTo == null
    }

    /**
     * Plan fuer [sourceRate] und den gewuenschten Faktor. Liegt Rate / Faktor ausserhalb dessen, was AudioTrack
     * kann (4-192 kHz), wird der Faktor angepasst; ergibt das keinen Faktor > 1, wird normal gespielt.
     */
    fun plan(sourceRate: Int, expansion: Int): Plan {
        val normal = Plan(sourceRate, 1, NORMAL_OUT_RATE, NORMAL_OUT_RATE)
        if (expansion <= 1 || sourceRate <= 0) return normal
        var e = expansion
        if (sourceRate.toDouble() / e > MAX_TRACK_RATE) e = Math.ceil(sourceRate.toDouble() / MAX_TRACK_RATE).toInt()
        if (sourceRate.toDouble() / e < MIN_TRACK_RATE) e = sourceRate / MIN_TRACK_RATE
        if (e <= 1) return normal
        return Plan(sourceRate, e, (sourceRate.toDouble() / e).roundToInt(), null)
    }

    /** Session-Position (s) aus Startoffset und gespielten AudioTrack-Frames */
    fun positionSec(offsetSec: Float, headFrames: Long, plan: Plan): Float =
        (offsetSec + headFrames.toDouble() / plan.positionRate).toFloat()

    /** Wanduhr-Dauer der Wiedergabe eines Session-Abschnitts von [sessionSec] Sekunden */
    fun playbackDurationSec(sessionSec: Float, plan: Plan): Float = sessionSec * plan.expansion
}

/**
 * T294: Streamendes Polyphasen-Resampling, Ergebnis gleich [AudioResampler.resamplePolyphase] ueber das ganze
 * Signal (bis auf Float-Rundung), aber blockweise mit begrenztem Speicher. Eingang/Ausgang 16-bit-Werte.
 */
class StreamingResampler(fromRate: Int, toRate: Int) {
    private val filter = AudioResampler.filterFor(fromRate, toRate)
    private val up = filter.up
    private val down = filter.down
    private val delay = filter.delay
    private val taps = filter.branches[0].size

    // Eingangspuffer: buf[0] = Eingangs-Sample mit Index bufStart
    private var buf = FloatArray(0)
    private var bufStart = 0L
    private var bufLen = 0
    private var inTotal = 0L
    private var outIndex = 0L

    /** Haengt [input] an und liefert alle Ausgangs-Samples, deren Eingang vollstaendig vorliegt. */
    fun process(input: ShortArray, len: Int = input.size): ShortArray {
        append(input, len)
        return emit(flush = false)
    }

    /** Rest am Ende (rechter Rand mit Nullen, wie das Gesamt-Resampling). */
    fun flush(): ShortArray = emit(flush = true)

    private fun append(input: ShortArray, len: Int) {
        if (bufLen + len > buf.size) {
            val n = FloatArray(maxOf(buf.size * 2, bufLen + len, 1024))
            System.arraycopy(buf, 0, n, 0, bufLen)
            buf = n
        }
        for (i in 0 until len) buf[bufLen + i] = input[i].toFloat()
        bufLen += len
        inTotal += len
    }

    private fun inputIndexFor(m: Long): Long {
        val j = m * down + delay
        val p = j % up
        return (j - p) / up
    }

    private fun emit(flush: Boolean): ShortArray {
        val outLimit = if (flush) (inTotal * up) / down else Long.MAX_VALUE
        val tmp = ShortArray(4096)
        var n = 0
        val parts = ArrayList<ShortArray>()
        while (outIndex < outLimit) {
            val j = outIndex * down + delay
            val p = (j % up).toInt()
            val i0 = (j - p) / up
            if (!flush && i0 >= inTotal) break
            val br = filter.branches[p]
            var acc = 0f
            for (k in 0 until taps) {
                val idx = i0 - k
                if (idx < 0 || idx >= inTotal) continue
                val local = (idx - bufStart).toInt()
                if (local < 0) continue
                acc += br[k] * buf[local]
            }
            tmp[n++] = Math.round(acc).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            if (n == tmp.size) { parts += tmp.copyOf(); n = 0 }
            outIndex++
        }
        if (n > 0) parts += tmp.copyOf(n)
        // Nicht mehr benoetigten Anfang des Puffers verwerfen
        val keepFrom = (inputIndexFor(outIndex) - (taps - 1)).coerceAtLeast(0L)
        val drop = (keepFrom - bufStart).coerceIn(0L, bufLen.toLong()).toInt()
        if (drop > 0) {
            System.arraycopy(buf, drop, buf, 0, bufLen - drop)
            bufLen -= drop
            bufStart += drop
        }
        val total = parts.sumOf { it.size }
        val merged = ShortArray(total)
        var pos = 0
        for (part in parts) { System.arraycopy(part, 0, merged, pos, part.size); pos += part.size }
        return merged
    }
}
