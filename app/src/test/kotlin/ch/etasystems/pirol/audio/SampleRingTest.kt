package ch.etasystems.pirol.audio

import ch.etasystems.pirol.audio.dsp.MelSpectrogram
import kotlin.math.sin
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SampleRingTest {

    private fun seq(from: Int, n: Int) = ShortArray(n) { (from + it).toShort() }

    @Test
    fun keepsOrderWithoutOverflow() {
        val r = SampleRing(10)
        r.write(seq(0, 4)); r.write(seq(4, 3))
        assertArrayEquals(seq(0, 7), r.toArray())
        assertFalse(r.overflowed)
    }

    @Test
    fun overflowKeepsNewestInOrder() {
        val r = SampleRing(10)
        r.write(seq(0, 7)); r.write(seq(7, 6))  // 13 Samples, Kapazitaet 10
        assertArrayEquals(seq(3, 10), r.toArray())
        assertTrue(r.overflowed)
    }

    @Test
    fun singleWriteLargerThanCapacity() {
        val r = SampleRing(5)
        r.write(seq(0, 12))
        assertArrayEquals(seq(7, 5), r.toArray())
        assertTrue(r.overflowed)
    }

    @Test
    fun clearResetsAndEmptyIsEmpty() {
        val r = SampleRing(5)
        assertEquals(0, r.toArray().size)
        r.write(seq(0, 9)); r.clear()
        assertEquals(0, r.size); assertFalse(r.overflowed)
        r.write(seq(1, 2))
        assertArrayEquals(seq(1, 2), r.toArray())
    }

    /** Backfill (Ring-Inhalt) + Live-Chunk auf demselben Mel-Zustand = identisch zu durchgehender Verarbeitung. */
    @Test
    fun backfillThenLiveEqualsContinuousProcessing() {
        val rate = 48000
        val all = ShortArray(rate * 2) { (8000 * sin(it * 0.05)).toInt().toShort() }
        val visible = all.copyOfRange(0, 20000)
        val hidden = all.copyOfRange(20000, 70000)
        val live = all.copyOfRange(70000, all.size)

        val ref = MelSpectrogram(rate).process(all)

        val mel = MelSpectrogram(rate)
        val out = ArrayList<FloatArray>()
        out += mel.process(visible)
        val ring = SampleRing(rate * 30)
        ring.write(hidden.copyOfRange(0, 25000)); ring.write(hidden.copyOfRange(25000, hidden.size))
        out += mel.process(ring.toArray())   // Backfill
        out += mel.process(live)             // danach live

        assertEquals(ref.size, out.size)
        for (i in ref.indices) assertArrayEquals(ref[i], out[i], 1e-4f)
    }
}
