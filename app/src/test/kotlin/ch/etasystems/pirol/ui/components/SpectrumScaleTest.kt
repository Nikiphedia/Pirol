package ch.etasystems.pirol.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** T275: Mel-/Linear-Achse und Gamma im Spektrum. */
class SpectrumScaleTest {
    @Test
    fun melRundreise() {
        for (hz in listOf(0f, 100f, 1000f, 8000f, 15000f)) {
            assertEquals(hz, melToHz(hzToMel(hz)), hz * 1e-3f + 1e-2f)
        }
    }

    @Test
    fun melAchseRandpunkteUndMonotonie() {
        assertEquals(10f, spectrumHzToX(500f, 500f, 10000f, 10f, 400f, SpectrumScale.MEL), 1e-3f)
        assertEquals(410f, spectrumHzToX(10000f, 500f, 10000f, 10f, 400f, SpectrumScale.MEL), 1e-3f)
        val mid = spectrumHzToX(5000f, 500f, 10000f, 10f, 400f, SpectrumScale.MEL)
        val lin = spectrumHzToX(5000f, 500f, 10000f, 10f, 400f, SpectrumScale.LINEAR)
        assertTrue(mid > lin) // Mel dehnt den tiefen Bereich
    }

    @Test
    fun xZuHzIstUmkehrung() {
        for (sc in SpectrumScale.entries) {
            val x = spectrumHzToX(3000f, 500f, 10000f, 34f, 600f, sc)
            assertEquals(3000f, spectrumXToHz(x, 500f, 10000f, 34f, 600f, sc), 1f)
        }
    }

    @Test
    fun melRasterUndRueckfall() {
        assertEquals(listOf(1000f, 2000f, 3000f, 4000f), spectrumFreqTicks(900f, 4500f, scale = SpectrumScale.MEL))
        // enger Bereich ohne zwei Mel-Marken: lineares Raster
        assertEquals(spectrumFreqTicks(2100f, 2900f), spectrumFreqTicks(2100f, 2900f, scale = SpectrumScale.MEL))
    }

    @Test
    fun gamma() {
        assertEquals(0.5f, spectrumGamma(0.5f, 1f), 1e-6f)
        assertEquals(0.25f, spectrumGamma(0.25f, 1f), 1e-6f)
        assertTrue(spectrumGamma(0.25f, 0.5f) > 0.25f)
        assertEquals(0f, spectrumGamma(-1f, 0.5f), 0f)
        assertEquals(1f, spectrumGamma(2f, 0.5f), 1e-6f)
    }
}
