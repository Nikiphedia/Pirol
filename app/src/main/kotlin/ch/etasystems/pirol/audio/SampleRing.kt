package ch.etasystems.pirol.audio

/**
 * T163: Billiger Ringpuffer fuer rohe PCM-Samples (ShortArray), keine Allokation pro Chunk.
 *
 * Sammelt waehrend der Live-Ansicht unsichtbar ist die eintreffenden Samples, damit das Sonogramm
 * beim Zurueckkehren in einem Schritt nachgerechnet werden kann. Das Array wird erst beim ersten
 * [write] angelegt. Laeuft der Puffer ueber, sind die aeltesten Samples weg ([overflowed] = true):
 * dann ist der Inhalt nicht mehr lueckenlos an den bisherigen Verlauf anschliessbar.
 *
 * Nicht thread-sicher: nur vom Collector-Coroutine-Kontext benutzen.
 */
class SampleRing(private val capacity: Int) {
    private var buf: ShortArray? = null
    private var head = 0       // naechste Schreibposition
    var size: Int = 0
        private set

    /** true, sobald seit dem letzten [clear] aelteste Samples ueberschrieben wurden. */
    var overflowed: Boolean = false
        private set

    init { require(capacity > 0) }

    fun write(samples: ShortArray) {
        if (samples.isEmpty()) return
        val b = buf ?: ShortArray(capacity).also { buf = it }
        var src = 0
        var n = samples.size
        if (n > capacity) {
            // Nur die letzten [capacity] Samples passen
            src = n - capacity
            n = capacity
            overflowed = true
        }
        val first = minOf(n, capacity - head)
        System.arraycopy(samples, src, b, head, first)
        if (n > first) System.arraycopy(samples, src + first, b, 0, n - first)
        head = (head + n) % capacity
        if (size + n > capacity) overflowed = true
        size = minOf(capacity, size + n)
    }

    /** Inhalt in zeitlicher Reihenfolge (aeltestes zuerst); allokiert ein neues Array. */
    fun toArray(): ShortArray {
        val b = buf ?: return ShortArray(0)
        val out = ShortArray(size)
        val start = (head - size + capacity) % capacity
        val first = minOf(size, capacity - start)
        System.arraycopy(b, start, out, 0, first)
        if (size > first) System.arraycopy(b, 0, out, first, size - first)
        return out
    }

    /** Leert den Puffer (das Array bleibt fuer die Wiederverwendung erhalten). */
    fun clear() {
        head = 0
        size = 0
        overflowed = false
    }
}
