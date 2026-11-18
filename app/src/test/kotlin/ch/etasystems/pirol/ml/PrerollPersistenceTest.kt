package ch.etasystems.pirol.ml

import ch.etasystems.pirol.audio.AudioChunk
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T118: Preroll laeuft als erster Block durch denselben Persistenz-/Analyse-Pfad wie Live-Audio.
 * Erwartung: persistiert = Preroll + Live, in Reihenfolge, genau einmal; Fenster ab Offset 0.
 */
class PrerollPersistenceTest {

    private val sr = 48000
    private val window = sr * 3

    /** Fortlaufendes Muster (Wert = Stream-Index mod 30000) ab Index [from] */
    private fun pattern(from: Int, n: Int) = ShortArray(n) { k -> ((from + k) % 30000).toShort() }

    private fun runSession(prerollSamples: Int, liveSamples: Int, hopMs: Int) {
        val acc = ChunkAccumulator(hopMs = hopMs)
        val persisted = mutableListOf<PersistHop>()
        val windows = mutableListOf<AnalysisWindow>()

        // 1. Preroll als ein Block (wie LiveViewModel.startInferencePipeline)
        val pre = acc.feed(AudioChunk(pattern(0, prerollSamples), sr, 0L))
        persisted += pre.hops
        windows += pre.windows

        // 2. Live-Chunks variabler Groesse (wie der T92-Emitter, ~100 ms)
        val sizes = intArrayOf(4800, 1023, 9600, 777, 4801)
        var pos = prerollSamples
        var i = 0
        while (pos < prerollSamples + liveSamples) {
            val n = minOf(sizes[i++ % sizes.size], prerollSamples + liveSamples - pos)
            val r = acc.feed(AudioChunk(pattern(pos, n), sr, 0L))
            persisted += r.hops
            windows += r.windows
            pos += n
        }
        // 3. Stopp: Rest schreiben (T117)
        acc.flushPending()?.let { persisted += it }

        // Preroll ist der Anfang der WAV: erster Hop beginnt bei 0 und enthaelt den ganzen Preroll
        assertEquals(0L, persisted.first().startIndex)
        assertTrue(persisted.first().pcm.size >= prerollSamples)

        // Genau einmal, lueckenlos, in Reihenfolge
        val total = prerollSamples + liveSamples
        assertEquals(total.toLong(), persisted.sumOf { it.pcm.size.toLong() })
        var expectedStart = 0L
        for (hop in persisted) {
            assertEquals(expectedStart, hop.startIndex)
            expectedStart += hop.pcm.size
        }
        val wav = ShortArray(total)
        var off = 0
        for (hop in persisted) { hop.pcm.copyInto(wav, off); off += hop.pcm.size }
        assertArrayEquals(pattern(0, total), wav)

        // Analyse-Fenster: Start bei 0 (= Preroll-Beginn in der WAV), Hop-Raster, Inhalt passt
        val hopSamples = sr.toLong() * hopMs / 1000
        assertEquals(((total - window) / hopSamples + 1).toInt(), windows.size)
        windows.forEachIndexed { k, w ->
            assertEquals(k * hopSamples, w.startIndex)
            assertEquals(window, w.pcm.size)
            assertEquals((w.startIndex % 30000).toInt().toShort(), w.pcm[0])
        }
        // Fenster, die vollstaendig im Preroll liegen, entstehen schon beim Preroll-Feed
        val prerollWindows = if (prerollSamples < window) 0 else ((prerollSamples - window) / hopSamples + 1).toInt()
        assertEquals(prerollWindows, pre.windows.size)
    }

    @Test
    fun preroll10sThenLive_overlap0() = runSession(sr * 10 + 1234, sr * 20 + 555, 3000)

    @Test
    fun preroll10sThenLive_overlap67() = runSession(sr * 10 + 1234, sr * 20 + 555, 1000)

    @Test
    fun preroll30sThenLive_overlap50() = runSession(sr * 30, sr * 7, 1500)

    @Test
    fun prerollShorterThanHop() = runSession(sr / 2, sr * 9, 3000)
}
