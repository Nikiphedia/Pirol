package ch.etasystems.pirol.ml

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * T231: Ringpuffer der letzten Sekunden Rohaudio (16-bit, Aufnahmerate) auf der Session-Achse.
 *
 * Index = Sample-Position seit Sessionbeginn (wie `chunkStartSec * sampleRate`). Der Puffer beginnt bei
 * [originIndex] (0 bei neuer Session, bisherige Laenge bei fortgesetzter Session) und behaelt die
 * letzten [capacity] Samples. Schreiben: Collector der Pipeline; Lesen: Zweitmodell-Coroutine.
 */
class AudioRingBuffer(
    val sampleRate: Int,
    val capacity: Int,
    val originIndex: Long = 0L
) {
    init {
        require(capacity > 0) { "capacity muss > 0 sein" }
    }

    companion object {
        /** T294: Obergrenze in Samples (= 30 s bei 96 kHz, 5.8 MB); bis 96 kHz also unveraendert Rate x Sekunden */
        const val MAX_SAMPLES = 2_880_000

        /** T294: so viele Sekunden bleiben auch bei hoher Rate mindestens im Puffer (Perch 5 s + Kontext) */
        const val MIN_SECONDS = 10

        /**
         * T294: Kapazitaet fuer [seconds] bei [sampleRate], gedeckelt auf [MAX_SAMPLES], aber nie unter
         * [MIN_SECONDS]. 384 kHz: 3.84 Mio. Samples (7.7 MB) statt 11.5 Mio. (23 MB).
         */
        fun capacityFor(sampleRate: Int, seconds: Int): Int {
            val wanted = sampleRate.toLong() * seconds
            val floor = sampleRate.toLong() * minOf(seconds, MIN_SECONDS)
            return maxOf(minOf(wanted, MAX_SAMPLES.toLong()), floor).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
        }
    }

    private val buf = ShortArray(capacity)
    private val _endIndex = MutableStateFlow(originIndex)

    /** Index hinter dem letzten geschriebenen Sample (beobachtbar, zum Warten auf kuenftiges Audio) */
    val endIndex: StateFlow<Long> = _endIndex.asStateFlow()

    /** Aeltester noch lesbarer Index */
    @Synchronized
    fun oldestIndex(): Long = maxOf(originIndex, _endIndex.value - capacity)

    /** Samples anhaengen (in Reihenfolge, lueckenlos) */
    @Synchronized
    fun append(samples: ShortArray) {
        if (samples.isEmpty()) return
        var end = _endIndex.value
        // Nur die letzten capacity Samples koennen ueberleben
        val skip = (samples.size - capacity).coerceAtLeast(0)
        end += skip
        // T250/N9: zwei Blockkopien (bis Pufferende, Rest ab 0) statt Sample fuer Sample mit Modulo
        var src = skip
        var pos = (end % capacity).toInt()
        while (src < samples.size) {
            val n = minOf(samples.size - src, capacity - pos)
            System.arraycopy(samples, src, buf, pos, n)
            src += n
            pos = (pos + n) % capacity
            end += n
        }
        _endIndex.value = end
    }

    /** Samples [from, to) lesen; null, wenn der Bereich nicht (mehr/noch) vollstaendig im Puffer liegt */
    @Synchronized
    fun read(from: Long, to: Long): ShortArray? {
        if (from > to || from < oldestIndex() || to > _endIndex.value) return null
        val out = ShortArray((to - from).toInt())
        var pos = (from % capacity).toInt()
        var dst = 0
        while (dst < out.size) {
            val n = minOf(out.size - dst, capacity - pos)
            System.arraycopy(buf, pos, out, dst, n)
            dst += n
            pos = (pos + n) % capacity
        }
        return out
    }
}
