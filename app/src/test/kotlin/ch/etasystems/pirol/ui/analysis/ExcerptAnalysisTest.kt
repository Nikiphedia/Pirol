package ch.etasystems.pirol.ui.analysis

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExcerptAnalysisTest {
    private fun tone(hz: Float, rate: Int, sec: Float, amp: Float = 0.5f) =
        ShortArray((rate * sec).toInt()) { (amp * 32767 * sin(2 * PI * hz * it / rate)).toInt().toShort() }

    @Test fun sliceWholeWithoutPosition() {
        assertEquals(0 to 48000, spectrumSliceRange(48000, 48000, 10f, null))
    }

    @Test fun sliceWholeOutsideExcerpt() {
        assertEquals(0 to 48000, spectrumSliceRange(48000, 48000, 10f, 5f))
        assertEquals(0 to 48000, spectrumSliceRange(48000, 48000, 10f, 12f))
    }

    @Test fun sliceAroundPosition() {
        // Position 0.5 s im Ausschnitt, Fenster 0.5 s -> 0.25..0.75 s
        assertEquals(12000 to 36000, spectrumSliceRange(96000, 48000, 10f, 10.5f))
    }

    @Test fun sliceClampedAtEdges() {
        val (a, b) = spectrumSliceRange(48000, 48000, 0f, 0.05f)
        assertEquals(0, a)
        assertTrue(b in 1..48000)
        val (c, d) = spectrumSliceRange(48000, 48000, 0f, 0.99f)
        assertEquals(48000, d)
        assertTrue(c < d)
    }

    @Test fun spectrumPeaksAtToneFrequency() {
        val rate = 48000
        val sp = computeExcerptSpectrum(tone(3000f, rate, 1f), 0, rate, 2048)!!
        val bin = sp.mean.indices.maxByOrNull { sp.mean[it] }!!
        assertEquals(3000f, bin * rate / 2048f, rate / 2048f * 1.5f)
        // Ein reiner Ton: Mittel ~ Maximum
        assertEquals(sp.max[bin], sp.mean[bin], 0.5f)
    }

    @Test fun maxExceedsMeanForBurst() {
        val rate = 48000
        val s = ShortArray(rate)
        val burst = tone(5000f, rate, 0.1f)
        burst.copyInto(s, 20000)
        val sp = computeExcerptSpectrum(s, 0, s.size, 2048)!!
        val bin = (5000f * 2048 / rate).toInt()
        assertTrue(sp.max[bin] > sp.mean[bin] + 3f)
    }

    @Test fun shortSliceStillComputed() {
        assertNotNull(computeExcerptSpectrum(tone(1000f, 48000, 0.01f), 0, 480, 2048))
    }

    @Test fun emptySliceNull() {
        assertNull(computeExcerptSpectrum(ShortArray(10), 5, 5, 512))
    }

    @Test fun binSize() {
        assertEquals(1, staticWaveformBinSize(1000))
        assertEquals(1, staticWaveformBinSize(4096))
        assertEquals(2, staticWaveformBinSize(4097))
        assertEquals(118, staticWaveformBinSize(480000))
    }

    @Test fun timeLabels() {
        assertEquals("12 s", analysisTimeLabel(12f))
        assertEquals("12.35 s", analysisTimeLabel(12.349f, 2))
        assertEquals("1:05", analysisTimeLabel(65f))
        assertEquals("1:05.50", analysisTimeLabel(65.5f, 2))
        assertEquals("1:00.00", analysisTimeLabel(59.999f, 2))
        assertEquals("0 s", analysisTimeLabel(-1f))
    }
}
