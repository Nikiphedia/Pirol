package ch.etasystems.pirol.ui.components

import ch.etasystems.pirol.audio.dsp.SpectrogramConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** T144: Frequenz-Marken, 12-kHz-Grenze und helle Palette. */
class SpectrogramAxisPaletteTest {

    @Test
    fun birdsConfig_reachesNyquist() {
        // T200: Mel bis Nyquist, Obergrenze haengt von der Abtastrate ab
        assertEquals(24000f, SpectrogramConfig.BIRDS.maxHz(48000), 0f)
        assertEquals(22050f, SpectrogramConfig.BIRDS.resolved(44100).fMax, 0f)
    }

    @Test
    fun ticks_fullRange_areFixedSet() {
        val ticks = computeFrequencyTicks(125f, 12000f).map { it.label }
        assertEquals(listOf("1k", "2k", "4k", "8k", "12k"), ticks)
    }

    @Test
    fun ticks_onlyVisibleRange() {
        val ticks = computeFrequencyTicks(2000f, 8000f).map { it.hz }
        assertEquals(listOf(2000f, 4000f, 8000f), ticks)
    }

    @Test
    fun tickFraction_isMelScaledAndMonotonic() {
        assertEquals(1f, frequencyFraction(12000f, 125f, 12000f), 1e-4f)
        val f1 = frequencyFraction(1000f, 125f, 12000f)
        val f4 = frequencyFraction(4000f, 125f, 12000f)
        val f8 = frequencyFraction(8000f, 125f, 12000f)
        assertTrue(f1 < f4 && f4 < f8 && f8 < 1f)
        // Mel ist nicht linear: 4 kHz liegt deutlich ueber 4/12 der Hoehe
        assertTrue(f4 > 4f / 12f)
    }

    @Test
    fun paperPalette_whiteQuietDarkLoud() {
        val lut = SpectrogramPalette.lutFor(SpectrogramPalette.PAPER)
        assertEquals(0xFFFFFFFF.toInt(), lut[0])
        assertEquals(0xFF000000.toInt(), lut[255])
        assertTrue(SpectrogramPalette.PAPER.isLight)
        assertTrue(!SpectrogramPalette.MAGMA.isLight)
    }
}
