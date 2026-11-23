package ch.etasystems.pirol.audio.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/** T200: Bandpass des Oszillogramms und Pegel (Peak/RMS dBFS, Uebersteuerung). */
class BandpassFilterTest {

    private val rate = 48000

    /** Verstaerkung in dB eines Sinus [hz] durch [f] (RMS nach Einschwingen). */
    private fun gainDb(f: BandpassFilter, hz: Double): Float {
        val n = rate  // 1 s
        var sumIn = 0.0
        var sumOut = 0.0
        for (i in 0 until n) {
            val x = sin(2.0 * PI * hz * i / rate).toFloat()
            val y = f.process(x)
            if (i >= n / 2) {  // zweite Haelfte: eingeschwungen
                sumIn += x * x
                sumOut += y * y
            }
        }
        return (10.0 * kotlin.math.log10(sumOut / sumIn)).toFloat()
    }

    @Test
    fun `Ton im Durchlass bleibt bei etwa 0 dB`() {
        val g = gainDb(BandpassFilter(rate, 1000f, 8000f), 3000.0)
        assertEquals("Gain $g dB", 0f, g, 0.5f)
    }

    @Test
    fun `Ton weit unterhalb wird stark gedaempft`() {
        // Wind: 50 Hz bei Hochpass 1 kHz (mehr als 4 Oktaven, 12 dB/Oktave)
        val g = gainDb(BandpassFilter(rate, 1000f, 8000f), 50.0)
        assertTrue("Gain $g dB", g < -40f)
    }

    @Test
    fun `Ton weit oberhalb wird stark gedaempft`() {
        val g = gainDb(BandpassFilter(rate, 1000f, 4000f), 20000.0)
        assertTrue("Gain $g dB", g < -20f)
    }

    @Test
    fun `voller Bereich ist ungefiltert`() {
        val f = BandpassFilter(rate, 0f, 24000f)
        assertTrue(f.isPassThrough)
        assertEquals(0.123f, f.process(0.123f), 0f)
        assertFalse(BandpassFilter(rate, 1000f, 24000f).isPassThrough)
    }

    @Test
    fun `Sinus Amplitude 1 ergibt Peak 0 dBFS und RMS -3_01 dBFS`() {
        val n = 48000
        var peak = 0f
        var sumSq = 0.0
        for (i in 0 until n) {
            val x = sin(2.0 * PI * 1000.0 * i / rate).toFloat()
            peak = maxOf(peak, abs(x))
            sumSq += x * x
        }
        assertEquals(0f, amplitudeToDbfs(peak), 0.01f)
        assertEquals(-3.01f, rmsDbfs(sumSq, n.toLong()), 0.01f)
        assertEquals(-20f, amplitudeToDbfs(0.1f), 0.01f)
        assertEquals(LEVEL_FLOOR_DBFS, amplitudeToDbfs(0f), 0f)
        assertEquals((20 * kotlin.math.log10(1 / sqrt(2.0))).toFloat(), rmsDbfs(0.5, 1), 0.01f)
    }

    @Test
    fun `Uebersteuerung ab 0_99 im Rohsignal`() {
        assertTrue(isClipping(0.99f))
        assertTrue(isClipping(1f))
        assertFalse(isClipping(0.98f))
    }
}
