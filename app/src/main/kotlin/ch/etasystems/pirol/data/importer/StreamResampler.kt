package ch.etasystems.pirol.data.importer

import ch.etasystems.pirol.audio.dsp.AudioResampler

/**
 * T255: Polyphasen-Resampler fuer Datenstroeme (Import langer Dateien in Bloecken).
 * Gleiches Filter und gleiche Rechnung wie [AudioResampler.resamplePolyphase]: Blockweise gefuettert
 * ergibt sich dasselbe Ergebnis wie in einem Stueck (Gruppenlaufzeit kompensiert, Raender mit Nullen).
 * Haelt nur die letzten Filterlaenge Eingangssamples im Speicher. Reines JVM.
 */
class StreamResampler(val fromRate: Int, val toRate: Int) {

    private val filter = AudioResampler.filterFor(fromRate, toRate)
    private val up = filter.up
    private val down = filter.down
    private val taps = filter.branches[0].size

    /** Eingang ab absolutem Index [bufStart] */
    private var buf = FloatArray(0)
    private var bufStart = 0L
    private var bufLen = 0
    /** Anzahl bisher empfangener Eingangssamples */
    private var received = 0L
    /** Index des naechsten Ausgangssamples */
    private var nextOut = 0L

    init { require(fromRate > 0 && toRate > 0 && fromRate != toRate) { "Raten ungueltig" } }

    /** Block anfuegen (normalisiert [-1,1]); liefert alle jetzt berechenbaren Ausgangssamples. */
    fun push(input: FloatArray): FloatArray {
        append(input)
        return produce(final = false)
    }

    /** Ende: restliche Ausgangssamples (mit Nullen hinter dem Ende) */
    fun finish(): FloatArray = produce(final = true)

    /** 16-bit-Variante */
    fun push(input: ShortArray): ShortArray = toShorts(push(FloatArray(input.size) { input[it] / 32768f }))
    fun finishShorts(): ShortArray = toShorts(finish())

    private fun append(input: FloatArray) {
        if (bufLen + input.size > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, bufLen + input.size))
        System.arraycopy(input, 0, buf, bufLen, input.size)
        bufLen += input.size
        received += input.size
    }

    private fun produce(final: Boolean): FloatArray {
        // Gesamtlaenge der Ausgabe ist erst am Ende bekannt (wie resamplePolyphase: size * up / down)
        val limit = if (final) (received * up) / down else Long.MAX_VALUE
        var result = FloatArray(0)
        var count = 0
        while (nextOut < limit) {
            val j = nextOut * down + filter.delay
            val p = (j % up).toInt()
            val i0 = (j - p) / up
            if (!final && i0 >= received) break  // juengstes benoetigtes Sample fehlt noch
            val br = filter.branches[p]
            var acc = 0f
            for (k in 0 until taps) {
                val idx = i0 - k
                if (idx < 0 || idx >= received) continue
                val local = (idx - bufStart).toInt()
                if (local < 0) continue  // bereits verworfen (nur Nullen-Rand bei sehr kurzem Filter)
                acc += br[k] * buf[local]
            }
            if (count == result.size) result = result.copyOf(maxOf(16, result.size * 2))
            result[count++] = acc
            nextOut++
        }
        // Nicht mehr benoetigte Eingangssamples verwerfen: das naechste Ausgangssample braucht ab i0 - (taps-1)
        val jNext = nextOut * down + filter.delay
        val keepFrom = ((jNext - (jNext % up)) / up - (taps - 1)).coerceAtLeast(0L)
        val drop = (keepFrom - bufStart).coerceIn(0L, bufLen.toLong()).toInt()
        if (drop > 0) {
            System.arraycopy(buf, drop, buf, 0, bufLen - drop)
            bufLen -= drop
            bufStart += drop
        }
        return result.copyOf(count)
    }

    private fun toShorts(f: FloatArray): ShortArray = ShortArray(f.size) {
        Math.round(f[it] * 32768f).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }
}
