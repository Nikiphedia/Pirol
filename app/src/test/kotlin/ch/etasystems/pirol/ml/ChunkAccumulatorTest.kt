package ch.etasystems.pirol.ml

import ch.etasystems.pirol.audio.AudioChunk
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T110: Fenster-Bildung mit Ueberlappung + Exactly-once-Persistenz.
 */
class ChunkAccumulatorTest {

    private val sr = 48000
    private val window = sr * 3  // 144'000

    /** Erzeugt Chunks variabler Groesse mit fortlaufendem Sample-Muster (Wert = Index mod 30000) */
    private fun chunks(total: Int, sizes: IntArray): List<AudioChunk> {
        val out = mutableListOf<AudioChunk>()
        var pos = 0
        var i = 0
        while (pos < total) {
            val n = minOf(sizes[i % sizes.size], total - pos)
            out.add(AudioChunk(ShortArray(n) { k -> ((pos + k) % 30000).toShort() }, sr, 0L))
            pos += n
            i++
        }
        return out
    }

    private fun run(acc: ChunkAccumulator, input: List<AudioChunk>): Pair<List<PersistHop>, List<AnalysisWindow>> {
        val hops = mutableListOf<PersistHop>()
        val windows = mutableListOf<AnalysisWindow>()
        for (c in input) {
            val r = acc.feed(c)
            hops += r.hops
            windows += r.windows
        }
        return hops to windows
    }

    private val varSizes = intArrayOf(4800, 1023, 9600, 777, 15000, 2048)

    private fun checkOverlap(hopMs: Int) {
        val total = sr * 20 + 1234
        val input = chunks(total, varSizes)
        val acc = ChunkAccumulator(hopMs = hopMs)
        val (hops, windows) = run(acc, input)
        val hopSamples = sr.toLong() * hopMs / 1000

        // Fenster: Starts im Hop-Raster, exakt 3 s, Inhalt = Stream an dieser Stelle
        val expectedCount = ((total - window) / hopSamples + 1).toInt()
        assertEquals(expectedCount, windows.size)
        windows.forEachIndexed { k, w ->
            assertEquals(k * hopSamples, w.startIndex)
            assertEquals(window, w.pcm.size)
            assertEquals(((w.startIndex) % 30000).toInt().toShort(), w.pcm[0])
            assertEquals(((w.startIndex + window - 1) % 30000).toInt().toShort(), w.pcm[window - 1])
        }

        // Persistenz: lueckenlos, in Reihenfolge, jedes Sample genau einmal
        var expectedStart = 0L
        for (h in hops) {
            assertEquals(expectedStart, h.startIndex)
            assertTrue(h.pcm.size >= hopSamples)
            expectedStart += h.pcm.size
        }
        val rest = acc.flushPending()
        val persisted = hops.sumOf { it.pcm.size.toLong() } + (rest?.pcm?.size ?: 0)
        assertEquals(total.toLong(), persisted)
        val all = (hops + listOfNotNull(rest)).flatMap { it.pcm.toList() }
        assertArrayEquals(ShortArray(total) { (it % 30000).toShort() }, all.toShortArray())
    }

    @Test fun `overlap 0 — Fenster alle 3 s, alle Samples genau einmal`() = checkOverlap(3000)
    @Test fun `overlap 50 — Fenster alle 1_5 s, alle Samples genau einmal`() = checkOverlap(1500)
    @Test fun `overlap 67 — Fenster jede Sekunde, alle Samples genau einmal`() = checkOverlap(1000)

    /** Referenz: altes ChunkAccumulator-Verhalten (ganze Chunks bis >= 3 s, dann Block). */
    private fun legacyBlocks(input: List<AudioChunk>): List<ShortArray> {
        val out = mutableListOf<ShortArray>()
        val buf = mutableListOf<ShortArray>()
        var n = 0
        for (c in input) {
            buf.add(c.samples); n += c.samples.size
            if (n >= window) {
                val b = ShortArray(n); var o = 0
                for (s in buf) { s.copyInto(b, o); o += s.size }
                out.add(b); buf.clear(); n = 0
            }
        }
        return out
    }

    @Test
    fun `overlap 0 — Persistenz-Bloecke identisch zum alten Verhalten`() {
        val input = chunks(sr * 31 + 999, varSizes)
        val (hops, _) = run(ChunkAccumulator(), input)
        val legacy = legacyBlocks(input)
        assertEquals(legacy.size, hops.size)
        legacy.forEachIndexed { i, b -> assertArrayEquals(b, hops[i].pcm) }
    }

    @Test
    fun `hop-Wechsel waehrend Stream — weiterhin exactly-once, Fenster monoton`() {
        val total = sr * 15
        val input = chunks(total, varSizes)
        val acc = ChunkAccumulator(hopMs = 1000)
        val hops = mutableListOf<PersistHop>()
        val windows = mutableListOf<AnalysisWindow>()
        input.forEachIndexed { i, c ->
            if (i == input.size / 2) acc.hopMs = 3000  // Fallback 67 → 0
            val r = acc.feed(c); hops += r.hops; windows += r.windows
        }
        val persisted = hops.sumOf { it.pcm.size.toLong() } + (acc.flushPending()?.pcm?.size ?: 0)
        assertEquals(total.toLong(), persisted)
        for (k in 1 until windows.size) assertTrue(windows[k].startIndex > windows[k - 1].startIndex)
        windows.forEach { w -> assertEquals((w.startIndex % 30000).toInt().toShort(), w.pcm[0]) }
    }

    @Test
    fun `reset — Stream-Index beginnt wieder bei 0`() {
        val acc = ChunkAccumulator(hopMs = 1500)
        run(acc, chunks(sr * 5, varSizes))
        acc.reset()
        val (hops, windows) = run(acc, chunks(sr * 4, varSizes))
        assertEquals(0L, hops.first().startIndex)
        assertEquals(0L, windows.first().startIndex)
        assertEquals(sr * 4L, acc.totalSamples)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `samplerate-Wechsel wirft`() {
        val acc = ChunkAccumulator()
        acc.feed(AudioChunk(ShortArray(100), 48000, 0L))
        acc.feed(AudioChunk(ShortArray(100), 44100, 0L))
    }
}
