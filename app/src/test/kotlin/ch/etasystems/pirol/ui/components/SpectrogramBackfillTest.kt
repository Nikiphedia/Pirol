package ch.etasystems.pirol.ui.components

import ch.etasystems.pirol.audio.dsp.DynamicRangeMapper
import ch.etasystems.pirol.audio.dsp.MelSpectrogram
import ch.etasystems.pirol.audio.dsp.SONOGRAM_DEFAULT_MAX_DBFS
import ch.etasystems.pirol.audio.dsp.SONOGRAM_DEFAULT_MIN_DBFS
import ch.etasystems.pirol.audio.dsp.SpectrogramConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

/**
 * T176: Sonogramm nach Rueckkehr in den Live-Tab / Backfill (T163).
 * Prueft die Faelle (a) Sprung > Fenster/Puffer, (b) clear/replace, (c) NaN/-Inf im Auto-Kontrast,
 * (d) gleichzeitiges Anhaengen (Default-Dispatcher) und Lesen (Renderer, Main-Thread).
 */
class SpectrogramBackfillTest {

    private fun frame(seq: Int, bins: Int = 4) = FloatArray(bins) { seq.toFloat() }
    private fun frames(from: Int, n: Int) = List(n) { frame(from + it) }

    /** Buchhaltung wie RingBitmapRenderer.render(). */
    private class RendererModel(val width: Int) {
        var lastAppended = 0L
        var lastStructure = -1
        fun render(state: SpectrogramState, forceFull: Boolean = false): RenderSnapshot {
            val snap = state.snapshotForRender(lastAppended, lastStructure, width, forceFull)
            lastAppended = snap.appendedTotal
            lastStructure = snap.structureVersion
            return snap
        }
    }

    // --- (a) Sprung von appendedTotal ueber Fenster bzw. Puffer hinaus ---

    @Test
    fun backfillGroesserAlsFensterBautNeuAuf() {
        val state = SpectrogramState(maxFrames = 3072)
        val r = RendererModel(width = 940)
        state.appendFrames(frames(0, 100))
        r.render(state)
        // Rueckkehr: 30 s Backfill (~2813 Frames) in einem Schritt
        state.appendFrames(frames(100, 2813))
        val snap = r.render(state)
        assertTrue(snap.full)
        assertEquals(940, snap.frames.size)
        assertEquals(2912f, snap.frames.last()[0])
        assertEquals(2912f - 939f, snap.frames.first()[0])
    }

    @Test
    fun sprungGroesserAlsPufferBautNeuAuf() {
        // Fenster breiter als der Puffer (theoretisch, z.B. hohe Samplerate) und Sprung > Puffer
        val state = SpectrogramState(maxFrames = 100)
        val r = RendererModel(width = 300)
        state.appendFrames(frames(0, 10))
        r.render(state)
        state.appendFrames(frames(10, 150))
        val snap = r.render(state)
        assertTrue("mehr neue Frames als im Puffer -> Neuaufbau", snap.full)
        assertEquals(100, snap.frames.size)
        assertEquals(159f, snap.frames.last()[0])
    }

    @Test
    fun inkrementellLiefertGenauDieNeuenFrames() {
        val state = SpectrogramState(maxFrames = 3072)
        val r = RendererModel(width = 940)
        state.appendFrames(frames(0, 50))
        r.render(state)
        state.appendFrames(frames(50, 9))
        val snap = r.render(state)
        assertFalse(snap.full)
        assertEquals((50 until 59).map { it.toFloat() }, snap.frames.map { it[0] })
    }

    // --- (b) clear() / replaceAll() waehrend der Renderer alte Indizes haelt ---

    @Test
    fun clearErzwingtNeuaufbauMitLeeremInhalt() {
        val state = SpectrogramState(maxFrames = 3072)
        val r = RendererModel(width = 940)
        state.appendFrames(frames(0, 500))
        r.render(state)
        state.clear()
        state.appendFrames(frames(500, 5))
        val snap = r.render(state)
        assertTrue(snap.full)
        assertEquals(listOf(500f, 501f, 502f, 503f, 504f), snap.frames.map { it[0] })
        // danach wieder inkrementell
        state.appendFrames(frames(505, 3))
        val next = r.render(state)
        assertFalse(next.full)
        assertEquals(listOf(505f, 506f, 507f), next.frames.map { it[0] })
    }

    @Test
    fun replaceAllIstAtomarUndBautNeuAuf() {
        val state = SpectrogramState(maxFrames = 3072)
        val r = RendererModel(width = 940)
        state.appendFrames(frames(0, 500))
        r.render(state)
        val genBefore = state.currentGeneration
        state.replaceAll(frames(1000, 2813))
        assertTrue(state.hasFrames)
        assertEquals(2813, state.availableFrames)
        assertTrue("verspaetetes Preroll wird verworfen", state.currentGeneration != genBefore)
        val snap = r.render(state)
        assertTrue(snap.full)
        assertEquals(940, snap.frames.size)
        assertEquals(3812f, snap.frames.last()[0])
    }

    @Test
    fun hasFramesFolgtDemInhalt() {
        val state = SpectrogramState(maxFrames = 16)
        assertFalse(state.hasFrames)
        state.appendFrames(emptyList())
        assertFalse(state.hasFrames)
        state.appendFrames(frames(0, 3))
        assertTrue(state.hasFrames)
        state.clear()
        assertFalse(state.hasFrames)
        state.prependFrames(frames(0, 2))
        assertTrue(state.hasFrames)
        state.replaceAll(emptyList())
        assertFalse(state.hasFrames)
    }

    // --- (c) NaN / -Inf ---

    @Test
    fun stilleErzeugtEndlicheMelWerte() {
        // T168: bei Lautsprecher-Wiedergabe kommen Null-Samples
        val config = SpectrogramConfig()
        val mel = MelSpectrogram(sampleRate = 48000, config = config)
        val out = mel.process(ShortArray(48000))
        assertTrue(out.isNotEmpty())
        for (f in out) for (v in f) assertTrue("Mel-Wert $v nicht endlich", v.isFinite())
    }

    private fun mapperWithClock(): DynamicRangeMapper {
        var t = 0L
        return DynamicRangeMapper(clockMs = { t += 300L; t })
    }

    @Test
    fun mapperIgnoriertNegativUnendlich() {
        val mapper = mapperWithClock()
        // 10 % der Werte -Inf: frueher wurde p2 = -Inf und blieb es in der IIR-Glaettung fuer immer
        repeat(200) { i ->
            mapper.update(FloatArray(10) { b -> if (b == 0) Float.NEGATIVE_INFINITY else -50f + (i % 7) })
        }
        val (lo, hi) = mapper.currentRange()
        assertTrue("lo=$lo", lo.isFinite())
        assertTrue("hi=$hi", hi.isFinite())
        assertTrue(hi > lo)
    }

    @Test
    fun mapperIgnoriertNaNUndErholtSich() {
        val mapper = mapperWithClock()
        // 10 % NaN: frueher wurde p98 = NaN -> range_hi NaN -> alles schwarz
        repeat(200) { mapper.update(FloatArray(10) { b -> if (b == 9) Float.NaN else -40f }) }
        val (lo, hi) = mapper.currentRange()
        assertTrue(lo.isFinite() && hi.isFinite())
        // nur NaN: Fallback statt NaN
        val nanOnly = mapperWithClock()
        repeat(200) { nanOnly.update(FloatArray(10) { Float.NaN }) }
        val r = nanOnly.currentRange()
        assertTrue(r.first.isFinite() && r.second.isFinite())
        // danach normale Werte -> normaler Bereich
        repeat(500) { nanOnly.update(FloatArray(10) { b -> -60f + b * 4f }) }
        val r2 = nanOnly.currentRange()
        assertTrue("lo=${r2.first}", r2.first > -61f && r2.first < -50f)
        assertTrue(r2.second > r2.first)
    }

    @Test
    fun computeStaticIgnoriertNichtEndliche() {
        val fs = List(50) { i -> FloatArray(10) { b -> if (b < 2) Float.NaN else -30f - i % 5 } }
        val (lo, hi) = DynamicRangeMapper.computeStatic(fs)
        assertTrue(lo.isFinite() && hi.isFinite() && hi > lo)
    }

    @Test
    fun finiteDbRangeFaelltZurueck() {
        assertEquals(SONOGRAM_DEFAULT_MIN_DBFS to SONOGRAM_DEFAULT_MAX_DBFS, finiteDbRange(Float.NaN, -10f))
        assertEquals(SONOGRAM_DEFAULT_MIN_DBFS to SONOGRAM_DEFAULT_MAX_DBFS, finiteDbRange(Float.NEGATIVE_INFINITY, -10f))
        assertEquals(SONOGRAM_DEFAULT_MIN_DBFS to SONOGRAM_DEFAULT_MAX_DBFS, finiteDbRange(-50f, Float.NaN))
        assertEquals(SONOGRAM_DEFAULT_MIN_DBFS to SONOGRAM_DEFAULT_MAX_DBFS, finiteDbRange(-50f, -50f))
        assertEquals(-50f to -20f, finiteDbRange(-50f, -20f))
    }

    // --- (d) Anhaengen auf einem Thread, Rendern auf einem anderen ---

    @Test
    fun gleichzeitigesAnhaengenUndRendernBleibtKonsistent() {
        val state = SpectrogramState(maxFrames = 3072)
        val stop = AtomicBoolean(false)
        val error = AtomicReference<Throwable?>(null)
        val writer = Thread {
            try {
                val rnd = Random(176)
                var seq = 0
                repeat(4000) {
                    when (rnd.nextInt(100)) {
                        0 -> { state.replaceAll(frames(seq, 2813)); seq += 2813 }   // Backfill nach Ueberlauf
                        1 -> { state.appendFrames(frames(seq, 2813)); seq += 2813 } // Backfill ohne Ueberlauf
                        2 -> state.clear()
                        else -> { val n = 1 + rnd.nextInt(12); state.appendFrames(frames(seq, n)); seq += n }
                    }
                }
            } catch (t: Throwable) {
                error.set(t)
            } finally {
                stop.set(true)
            }
        }
        val r = RendererModel(width = 940)
        var lastSeq: Float? = null  // null = unbekannt (Start oder leerer Neuaufbau nach clear)
        var renders = 0
        writer.start()
        while (!stop.get() || renders < 10) {
            val snap = r.render(state)
            renders++
            val seqs = snap.frames.map { it[0] }
            // Frames innerhalb eines Snapshots sind lueckenlos und aufsteigend
            for (i in 1 until seqs.size) assertEquals(seqs[i - 1] + 1f, seqs[i])
            val prev = lastSeq
            if (!snap.full && seqs.isNotEmpty() && prev != null) {
                // inkrementell: schliesst genau an das zuletzt Gezeichnete an
                assertEquals(prev + 1f, seqs.first())
            }
            if (snap.full) assertTrue(snap.frames.size <= 940)
            if (seqs.isNotEmpty()) lastSeq = seqs.last()
            else if (snap.full) lastSeq = null
        }
        writer.join()
        assertNull(error.get())
        assertTrue(renders > 0)
    }
}
