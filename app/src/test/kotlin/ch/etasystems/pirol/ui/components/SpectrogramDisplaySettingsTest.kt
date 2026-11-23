package ch.etasystems.pirol.ui.components

import ch.etasystems.pirol.audio.dsp.DynamicRangeMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T107: reine Logik der Sonogramm-Darstellung (Presets, Mindestspanne, Frequenz-Zuschnitt). */
class SpectrogramDisplaySettingsTest {

    @Test
    fun presets_areDistinctAndRecognized() {
        val base = SpectrogramDisplaySettings()
        SpectrogramPreset.entries.forEach { p ->
            assertEquals(p, base.withPreset(p).matchingPreset())
        }
        val quiet = base.withPreset(SpectrogramPreset.LEISE_RUFE)
        assertTrue(quiet.autoContrast)
        assertTrue(quiet.autoMinSpanDb >= 30f)
        assertTrue(quiet.gamma < base.withPreset(SpectrogramPreset.STANDARD).gamma)
        val loud = base.withPreset(SpectrogramPreset.LAUTE_UMGEBUNG)
        assertFalse(loud.autoContrast)
        assertEquals(-85f, loud.minDb, 0f)
        assertEquals(1000f, loud.freqMinHz, 0f)
    }

    @Test
    fun preset_keepsPaletteAndWindow() {
        val s = SpectrogramDisplaySettings(palette = SpectrogramPalette.VIRIDIS, windowSec = 7f)
            .withPreset(SpectrogramPreset.LEISE_RUFE)
        assertEquals(SpectrogramPalette.VIRIDIS, s.palette)
        assertEquals(7f, s.windowSec, 0f)
    }

    @Test
    fun customValues_matchNoPreset() {
        assertNull(SpectrogramDisplaySettings(gamma = 0.9f).matchingPreset())
    }

    @Test
    fun effectiveRange_enforcesMinSpanAfterCeiling() {
        // Ceiling -45 bei minDb -50 wuerde nur 5 dB Spanne lassen
        val (lo, hi) = effectiveDbRange(-50f, 0f, -45f)
        assertEquals(-50f, lo, 0f)
        assertEquals(-40f, hi, 0.001f)
    }

    @Test
    fun effectiveRange_noCeilingEffectAtZero() {
        assertEquals(-80f to 0f, effectiveDbRange(-80f, 0f, 0f))
        assertEquals(-80f to -20f, effectiveDbRange(-80f, 0f, -20f))
    }

    @Test
    fun visibleBinRange_fullRangeCoversAllBins() {
        val r = visibleBinRange(128, 125f, 8000f, 0f, 24000f)
        assertEquals(0..127, r)
    }

    @Test
    fun visibleBinRange_highPassCutsLowBins() {
        val r = visibleBinRange(128, 125f, 8000f, 1000f, 8000f)
        assertTrue(r.first > 20)
        assertEquals(127, r.last)
    }

    @Test
    fun visibleBinRange_neverBelowMinimum() {
        val r = visibleBinRange(128, 125f, 8000f, 3000f, 3001f)
        assertTrue(r.last - r.first + 1 >= MIN_VISIBLE_BINS)
        assertTrue(r.first >= 0 && r.last <= 127)
    }

    @Test
    fun staticRange_respectsMinSpan() {
        // Fast konstantes Rauschen: ohne Mindestspanne waeren es ~1 dB
        val frames = List(100) { FloatArray(128) { -60f + (it % 2) * 0.1f } }
        val (lo, hi) = DynamicRangeMapper.computeStatic(frames, minSpanDb = 30f)
        assertTrue(hi - lo >= 30f)
    }

    @Test
    fun staticRange_higherLowPercentileRaisesFloor() {
        val frames = List(100) { FloatArray(128) { b -> -80f + b * 0.5f } }
        val std = DynamicRangeMapper.computeStatic(frames)
        val quiet = DynamicRangeMapper.computeStatic(frames, lowPercentile = 0.30f, highPercentile = 0.995f)
        assertTrue(quiet.first > std.first)
    }
}
