package ch.etasystems.pirol.audio

import org.junit.Assert.assertEquals
import org.junit.Test

/** T122: Wiedergabe auf -10 dBFS normalisieren, Verstaerkung begrenzt. */
class PlaybackGainTest {

    private fun pcm(vararg v: Int) = ByteArray(v.size * 2).also { b ->
        v.forEachIndexed { i, s -> b[2 * i] = (s and 0xFF).toByte(); b[2 * i + 1] = ((s shr 8) and 0xFF).toByte() }
    }
    private fun sample(b: ByteArray, i: Int) = ((b[2 * i].toInt() and 0xFF) or (b[2 * i + 1].toInt() shl 8)).toShort().toInt()

    @Test
    fun `leise Spitze wird auf -10 dBFS angehoben`() {
        val b = pcm(1000, -500, 0)
        PlaybackGain.normalizeInPlace(b, -10f, 30f)
        assertEquals(10362, sample(b, 0), 2)
        assertEquals(-5181, sample(b, 1), 2)
    }

    @Test
    fun `Verstaerkung ist auf 30 dB begrenzt`() {
        val b = pcm(10)
        val g = PlaybackGain.normalizeInPlace(b, -10f, 30f)
        assertEquals(31.62f, g, 0.05f)
    }

    @Test
    fun `Stille bleibt unveraendert`() {
        val b = pcm(0, 0)
        assertEquals(1f, PlaybackGain.normalizeInPlace(b), 0f)
    }
}
