package ch.etasystems.pirol.ml

import ch.etasystems.pirol.audio.AudioChunk

/**
 * Audio-Abschnitt fuer die Persistenz (WAV). Jedes Eingangs-Sample landet in GENAU EINEM Hop.
 *
 * @param startIndex Stream-Index des ersten Samples (Samples seit letztem reset())
 */
class PersistHop(
    val pcm: ShortArray,
    val sampleRate: Int,
    val startIndex: Long
)

/**
 * Analyse-Fenster fester Laenge (Fensterlaenge des Modells, BirdNET 3: 3 s) — kann sich mit Nachbarfenstern ueberlappen (T110).
 *
 * @param startIndex Stream-Index des ersten Samples im Fenster
 */
class AnalysisWindow(
    val pcm: ShortArray,
    val sampleRate: Int,
    val startIndex: Long
)

/** Ergebnis eines feed()-Aufrufs: fertige Persistenz-Hops und fertige Analyse-Fenster (je 0..n). */
class FeedResult(
    val hops: List<PersistHop>,
    val windows: List<AnalysisWindow>
)

/**
 * Trennt den Audio-Strom in zwei unabhaengige Ausgaben (T110):
 *
 * 1. **Persistenz-Hops:** Chunks werden gesammelt, sobald mindestens [hopMs] Audio vorliegt,
 *    werden ALLE gesammelten Samples als ein Hop freigegeben. Jedes Sample genau einmal,
 *    in Reihenfolge. Bei hopMs = 3000 (Overlap 0 %) identisch zum frueheren Verhalten
 *    (Block = ganze Chunks bis >= 3 s, keine Kuerzung).
 * 2. **Analyse-Fenster:** exakt [windowDurationMs] lange Fenster, Starts auf dem Raster
 *    0, hop, 2*hop, ... (Stream-Index). Bei Overlap 0 % schliessen die Fenster lueckenlos an.
 *
 * Nicht thread-safe — wird nur vom Collector-Coroutine des InferenceWorker benutzt.
 */
class ChunkAccumulator(
    /** Fensterlaenge in ms (T151a: laut Modellprofil, Default 3 s) */
    val windowDurationMs: Int = CHUNK_DURATION_MS.toInt(),
    hopMs: Int = windowDurationMs
) {
    /** Abstand zwischen zwei Fensterstarts in ms (= Persistenz-Granularitaet). 1..windowDurationMs. */
    var hopMs: Int = hopMs
        set(value) {
            require(value in 1..windowDurationMs) { "hopMs ausserhalb 1..$windowDurationMs: $value" }
            field = value
        }

    private var currentSampleRate: Int? = null

    // --- Persistenz ---
    private val pendingChunks = mutableListOf<ShortArray>()
    private var pendingCount = 0
    private var persistedTotal = 0L

    // --- Fenster-Historie ---
    private var hist = ShortArray(0)
    private var histStart = 0L    // Stream-Index von hist[0]
    private var histLen = 0
    private var nextWindowStart = 0L

    /** Gesamtzahl eingespeister Samples seit reset() */
    var totalSamples: Long = 0L
        private set

    init {
        this.hopMs = hopMs
    }

    /**
     * Chunk einspeisen.
     * @throws IllegalArgumentException bei Samplerate-Wechsel (Aufrufer: flushPending() + reset())
     */
    fun feed(chunk: AudioChunk): FeedResult {
        val rate = currentSampleRate
        if (rate != null && rate != chunk.sampleRate) {
            throw IllegalArgumentException(
                "Inkonsistente Samplerate: erwartet $rate, erhalten ${chunk.sampleRate}"
            )
        }
        val sr = chunk.sampleRate
        currentSampleRate = sr
        val samples = chunk.samples
        totalSamples += samples.size

        // 1. Persistenz (jedes Sample genau einmal)
        val hopSamples = sr.toLong() * hopMs / 1000
        val hops = if (samples.isEmpty()) emptyList() else {
            pendingChunks.add(samples)
            pendingCount += samples.size
            if (pendingCount >= hopSamples) listOf(buildHop(sr)) else emptyList()
        }

        // 2. Fenster-Historie anhaengen
        appendHistory(samples)
        val windowSamples = (sr.toLong() * windowDurationMs / 1000).toInt()
        val windows = mutableListOf<AnalysisWindow>()
        while (nextWindowStart + windowSamples <= totalSamples) {
            val off = (nextWindowStart - histStart).toInt()
            windows.add(AnalysisWindow(hist.copyOfRange(off, off + windowSamples), sr, nextWindowStart))
            nextWindowStart += hopSamples
        }
        if (windows.isNotEmpty()) discardHistoryBefore(nextWindowStart)

        return FeedResult(hops, windows)
    }

    /** Noch nicht persistierte Samples als Hop freigeben (z.B. vor reset() bei Samplerate-Wechsel). */
    fun flushPending(): PersistHop? {
        val sr = currentSampleRate ?: return null
        if (pendingCount == 0) return null
        return buildHop(sr)
    }

    /** Internen State zuruecksetzen (nicht persistierte Samples werden verworfen). */
    fun reset() {
        pendingChunks.clear()
        pendingCount = 0
        persistedTotal = 0L
        hist = ShortArray(0)
        histStart = 0L
        histLen = 0
        nextWindowStart = 0L
        totalSamples = 0L
        currentSampleRate = null
    }

    private fun buildHop(sr: Int): PersistHop {
        val combined = ShortArray(pendingCount)
        var offset = 0
        for (buf in pendingChunks) {
            buf.copyInto(combined, destinationOffset = offset)
            offset += buf.size
        }
        val hop = PersistHop(combined, sr, persistedTotal)
        persistedTotal += pendingCount
        pendingChunks.clear()
        pendingCount = 0
        return hop
    }

    private fun appendHistory(samples: ShortArray) {
        val needed = histLen + samples.size
        if (needed > hist.size) {
            hist = hist.copyOf(maxOf(needed, hist.size * 2))
        }
        samples.copyInto(hist, destinationOffset = histLen)
        histLen = needed
    }

    private fun discardHistoryBefore(streamIndex: Long) {
        val drop = (streamIndex - histStart).toInt().coerceAtMost(histLen)
        if (drop <= 0) return
        hist.copyInto(hist, destinationOffset = 0, startIndex = drop, endIndex = histLen)
        histLen -= drop
        histStart += drop
    }
}
