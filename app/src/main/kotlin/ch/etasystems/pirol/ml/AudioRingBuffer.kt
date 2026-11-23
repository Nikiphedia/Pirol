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
        for (i in skip until samples.size) {
            buf[(end % capacity).toInt()] = samples[i]
            end++
        }
        _endIndex.value = end
    }

    /** Samples [from, to) lesen; null, wenn der Bereich nicht (mehr/noch) vollstaendig im Puffer liegt */
    @Synchronized
    fun read(from: Long, to: Long): ShortArray? {
        if (from > to || from < oldestIndex() || to > _endIndex.value) return null
        val out = ShortArray((to - from).toInt())
        for (i in out.indices) out[i] = buf[((from + i) % capacity).toInt()]
        return out
    }
}
