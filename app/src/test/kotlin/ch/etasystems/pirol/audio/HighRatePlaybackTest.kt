package ch.etasystems.pirol.audio

import ch.etasystems.pirol.audio.dsp.AudioResampler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** T294: Hochraten-Wiedergabe: Plan, Zeitabbildung (Playhead) und streamendes Resampling. */
class HighRatePlaybackTest {

    @Test
    fun onlyAbove96kHz() {
        assertFalse(HighRatePlayback.applies(48_000))
        assertFalse(HighRatePlayback.applies(96_000))
        assertTrue(HighRatePlayback.applies(192_000))
        assertTrue(HighRatePlayback.applies(500_000))
    }

    @Test
    fun timeExpansionPlan_384k() {
        val p = HighRatePlayback.plan(384_000, 10)
        assertEquals(10, p.expansion)
        assertEquals(38_400, p.outRate)
        assertNull(p.resampleTo)
        assertTrue(p.timeExpanded)
        // Kopfposition zaehlt Quell-Frames: 38'400 gespielte Frames = 0.1 s Session-Zeit (1 s Wanduhr)
        assertEquals(12.1f, HighRatePlayback.positionSec(12f, 38_400, p), 1e-4f)
        assertEquals(30f, HighRatePlayback.playbackDurationSec(3f, p), 1e-4f)
    }

    @Test
    fun normalPlan_downsamplesTo48k() {
        val p = HighRatePlayback.plan(500_000, 1)
        assertEquals(1, p.expansion)
        assertEquals(48_000, p.outRate)
        assertEquals(48_000, p.resampleTo)
        assertEquals(5.5f, HighRatePlayback.positionSec(5f, 24_000, p), 1e-4f)
        assertEquals(3f, HighRatePlayback.playbackDurationSec(3f, p), 1e-4f)
    }

    @Test
    fun factorAdjustedToTrackLimits() {
        // 500 kHz / 2 = 250 kHz > 192 kHz -> Faktor 3
        val p = HighRatePlayback.plan(500_000, 2)
        assertEquals(3, p.expansion)
        assertTrue(p.outRate <= HighRatePlayback.MAX_TRACK_RATE)
        // 192 kHz / 100 = 1.92 kHz < 4 kHz -> Faktor 48
        val q = HighRatePlayback.plan(192_000, 100)
        assertEquals(48, q.expansion)
        assertEquals(4_000, q.outRate)
    }

    @Test
    fun sanitizeAndToggleCycle() {
        assertEquals(10, HighRatePlayback.sanitize(0))
        assertEquals(10, HighRatePlayback.sanitize(-3))
        assertEquals(20, HighRatePlayback.sanitize(20))
        assertEquals(20, ch.etasystems.pirol.ui.analysis.nextExpansion(10))
        assertEquals(1, ch.etasystems.pirol.ui.analysis.nextExpansion(20))
        assertEquals(10, ch.etasystems.pirol.ui.analysis.nextExpansion(1))
        assertEquals(10, ch.etasystems.pirol.ui.analysis.nextExpansion(7))
    }

    private fun signal(n: Int, rate: Int): ShortArray = ShortArray(n) { i ->
        val t = i.toDouble() / rate
        (8000 * sin(2 * PI * 3000 * t) + 6000 * sin(2 * PI * 45000 * t) + ((i * 7919) % 2001 - 1000)).toInt().toShort()
    }

    private fun streamed(input: ShortArray, from: Int, to: Int, chunks: IntArray): ShortArray {
        val r = StreamingResampler(from, to)
        val out = ArrayList<Short>()
        var pos = 0
        var c = 0
        while (pos < input.size) {
            val len = minOf(chunks[c % chunks.size], input.size - pos)
            r.process(input.copyOfRange(pos, pos + len)).forEach { out += it }
            pos += len
            c++
        }
        r.flush().forEach { out += it }
        return out.toShortArray()
    }

    private fun assertMatchesWhole(from: Int, to: Int) {
        val input = signal(from / 5, from)  // 0.2 s
        val whole = AudioResampler.resamplePolyphase(FloatArray(input.size) { input[it].toFloat() }, from, to)
        val s = streamed(input, from, to, intArrayOf(4096, 777, 13_001, 1))
        assertEquals(whole.size, s.size)
        var maxErr = 0f
        for (i in whole.indices) {
            val ref = Math.round(whole[i]).coerceIn(-32768, 32767)
            maxErr = maxOf(maxErr, abs(ref - s[i]).toFloat())
        }
        assertTrue("max. Abweichung $maxErr", maxErr <= 1f)
    }

    @Test
    fun streamingResamplerEqualsWhole_384k() = assertMatchesWhole(384_000, 48_000)

    @Test
    fun streamingResamplerEqualsWhole_312k5() = assertMatchesWhole(312_500, 48_000)

    @Test
    fun streamingResamplerRemovesUltrasound() {
        val rate = 384_000
        val n = rate / 2
        val tone = ShortArray(n) { (10000 * sin(2 * PI * 45000 * it / rate)).toInt().toShort() }
        val out = streamed(tone, rate, 48_000, intArrayOf(65_536))
        // nach dem Einschwingen: 45 kHz liegt weit ueber 24 kHz Nyquist -> stark gedaempft
        var peak = 0
        for (i in 2000 until out.size - 2000) peak = maxOf(peak, abs(out[i].toInt()))
        assertTrue("Restpegel $peak", peak < 10000 / 100)
    }
}
