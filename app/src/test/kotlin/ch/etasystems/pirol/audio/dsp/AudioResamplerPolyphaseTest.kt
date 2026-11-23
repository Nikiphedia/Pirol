package ch.etasystems.pirol.audio.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue

/** T133: Polyphasen-Resampler (Pegel, Alias-Daempfung, Laenge, Laufzeit-Ausrichtung, LINEAR unveraendert). */
class AudioResamplerPolyphaseTest {

    private fun tone(freq: Double, rate: Int, n: Int) =
        FloatArray(n) { (0.5 * sin(2.0 * PI * freq * it / rate)).toFloat() }

    /** RMS-Pegel in dB (Mittelteil, Randeffekte ausgeschlossen) */
    private fun rmsDb(x: FloatArray): Double {
        val a = x.size / 4
        val b = x.size - a
        var s = 0.0
        for (i in a until b) s += x[i] * x[i]
        return 20 * log10(sqrt(s / (b - a)) + 1e-12)
    }

    private fun gainDb(freq: Double, from: Int, to: Int): Double {
        val x = tone(freq, from, from * 3)
        return rmsDb(AudioResampler.resamplePolyphase(x, from, to)) - rmsDb(x)
    }

    @Test fun passbandTonesWithinHalfDb() {
        for (f in doubleArrayOf(1000.0, 6000.0)) {
            val g = gainDb(f, 48000, 32000)
            assertTrue("f=$f gain=$g", abs(g) <= 0.5)
        }
    }

    @Test fun aliasTonesAttenuated() {
        for (f in doubleArrayOf(17000.0, 19000.0, 22000.0)) {
            val g = gainDb(f, 48000, 32000)
            println("T133 stopband f=$f gain=${"%.1f".format(g)} dB")
            assertTrue("f=$f gain=$g", g <= -40.0)
        }
    }

    @Test fun lengthAndDc() {
        val x = FloatArray(144000) { 0.5f }
        val y = AudioResampler.resamplePolyphase(x, 48000, 32000)
        assertEquals(96000, y.size)
        assertTrue(abs(y[48000] - 0.5f) < 0.01f)
        val z = AudioResampler.resamplePolyphase(FloatArray(441000), 44100, 32000)
        assertEquals(320000, z.size)
        assertEquals(48000, AudioResampler.resamplePolyphase(FloatArray(96000), 96000, 48000).size)
        assertEquals(0, AudioResampler.resamplePolyphase(FloatArray(0), 48000, 32000).size)
    }

    @Test fun groupDelayAligned() {
        for ((from, to) in listOf(48000 to 32000, 44100 to 32000, 96000 to 32000)) {
            val i = 30000
            val x = FloatArray(90000)
            x[i] = 1f
            val y = AudioResampler.resamplePolyphase(x, from, to)
            var peak = 0
            for (k in y.indices) if (abs(y[k]) > abs(y[peak])) peak = k
            val expected = i.toDouble() * to / from
            assertTrue("$from->$to peak=$peak expected=$expected", abs(peak - expected) <= 1.0)
        }
    }

    @Test fun linearModeUnchanged() {
        val x = tone(1000.0, 48000, 48000)
        assertArrayEquals(AudioResampler.resample(x, 48000, 32000), AudioResampler.resample(x, 48000, 32000, AudioResampler.Mode.LINEAR), 0f)
        val s = ShortArray(4800) { (it * 7 % 2000).toShort() }
        assertArrayEquals(AudioResampler.resample(s, 48000, 32000), AudioResampler.resample(s, 48000, 32000, AudioResampler.Mode.LINEAR), 0f)
    }

    @Test fun sameRatePassThrough() {
        val x = tone(1000.0, 32000, 1000)
        assertArrayEquals(x, AudioResampler.resample(x, 32000, 32000, AudioResampler.Mode.POLYPHASE), 0f)
        println("T133 taps 48->32=${AudioResampler.tapsFor(48000, 32000)}")
    }

    /** T208: Moduswechsel ueber defaultMode wirkt beim naechsten Resample-Aufruf sofort. */
    @Test fun modeSwitchTakesEffectImmediately() {
        val x = tone(17000.0, 48000, 48000)
        val old = AudioResampler.defaultMode
        try {
            assertEquals(AudioResampler.Mode.LINEAR, AudioResampler.applyMode("LINEAR"))
            val lin = AudioResampler.resample(x, 48000, 32000, AudioResampler.defaultMode)
            assertArrayEquals(AudioResampler.resample(x, 48000, 32000), lin, 0f)
            assertEquals(AudioResampler.Mode.POLYPHASE, AudioResampler.applyMode("POLYPHASE"))
            val poly = AudioResampler.resample(x, 48000, 32000, AudioResampler.defaultMode)
            assertArrayEquals(AudioResampler.resamplePolyphase(x, 48000, 32000), poly, 0f)
            // 17-kHz-Ton ist oberhalb des neuen Nyquist: Polyphase daempft, Linear nicht
            assertTrue(rmsDb(poly) < rmsDb(lin) - 30.0)
            assertEquals(AudioResampler.Mode.POLYPHASE, AudioResampler.applyMode(null))
            assertEquals(AudioResampler.Mode.POLYPHASE, AudioResampler.applyMode("quatsch"))
        } finally {
            AudioResampler.defaultMode = old
        }
    }
}
