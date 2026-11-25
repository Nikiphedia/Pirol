package ch.etasystems.pirol.audio.dsp

import ch.etasystems.pirol.ui.analysis.HighRateZoom
import ch.etasystems.pirol.ui.components.SpectrogramDisplaySettings
import ch.etasystems.pirol.ui.components.computeFrequencyTicks
import ch.etasystems.pirol.ui.components.displayBinRange
import ch.etasystems.pirol.ui.components.frequencyFraction
import ch.etasystems.pirol.ui.components.sharedFreqRange
import ch.etasystems.pirol.ui.components.visibleBinRange
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/** T294: Anzeige von Ultraschall-Sessions (Bereichswahl, lineare Achse); bis 96 kHz unveraendert. */
class UltrasoundDisplayTest {

    @Test
    fun configBySessionRate() {
        assertSame(SpectrogramConfig.BIRDS, SpectrogramConfig.forSessionRate(48_000))
        assertSame(SpectrogramConfig.BIRDS, SpectrogramConfig.forSessionRate(96_000))
        val u = SpectrogramConfig.forSessionRate(384_000)
        assertTrue(u.linearFreq)
        assertEquals(192_000f, u.resolved(384_000).fMax, 0f)
        assertFalse(SpectrogramConfig.BIRDS.linearFreq)
    }

    @Test
    fun defaultUltrasoundRange_10to150kHz() {
        val s = SpectrogramDisplaySettings()
        val cfg = SpectrogramConfig.ULTRASOUND.resolved(384_000)
        val shown = s.forConfig(cfg)
        assertEquals(10_000f, shown.freqMinHz, 0f)
        assertEquals(150_000f, shown.freqMaxHz, 0f)
        // Vogel-Config: unveraendert
        assertSame(s, s.forConfig(SpectrogramConfig.BIRDS.resolved(48_000)))
        assertEquals(10_000f to 150_000f, sharedFreqRange(s, cfg, 384_000))
        // 192 kHz: Nyquist 96 kHz begrenzt
        assertEquals(10_000f to 96_000f, sharedFreqRange(s, SpectrogramConfig.ULTRASOUND.resolved(192_000), 192_000))
    }

    @Test
    fun withFreqRangeKeepsBirdRange() {
        val s = SpectrogramDisplaySettings(freqMinHz = 1000f, freqMaxHz = 12000f)
        val u = s.withFreqRange(SpectrogramConfig.ULTRASOUND, 20_000f, 120_000f)
        assertEquals(1000f, u.freqMinHz, 0f)
        assertEquals(12000f, u.freqMaxHz, 0f)
        assertEquals(20_000f, u.ultraFreqMinHz, 0f)
        val b = s.withFreqRange(SpectrogramConfig.BIRDS, 500f, 8000f)
        assertEquals(500f, b.freqMinHz, 0f)
        assertEquals(SpectrogramConfig.ULTRASOUND_DISPLAY_MIN_HZ, b.ultraFreqMinHz, 0f)
    }

    @Test
    fun linearBinRangeAndFraction() {
        val cfg = SpectrogramConfig.ULTRASOUND.resolved(384_000)
        val r = displayBinRange(cfg, 10_000f, 150_000f)
        assertEquals(13, r.first)   // floor(10/192 * 256)
        assertEquals(199, r.last)   // ceil(150/192 * 256) - 1
        assertEquals(0.5f, frequencyFraction(80_000f, 10_000f, 150_000f, linear = true), 1e-5f)
        // Mel-Variante unveraendert
        assertEquals(visibleBinRange(128, 125f, 8000f, 1000f, 8000f), visibleBinRange(128, 125f, 8000f, 1000f, 8000f, false))
    }

    @Test
    fun linearTicks() {
        val ticks = computeFrequencyTicks(10_000f, 150_000f, linear = true).map { it.label }
        assertEquals(listOf("20k", "40k", "60k", "80k", "100k", "120k", "140k"), ticks)
        // Vogel-Ticks unveraendert
        assertEquals(computeFrequencyTicks(0f, 24000f), computeFrequencyTicks(0f, 24000f, linear = false))
    }

    @Test
    fun linearFilterbankPeaksAtTone() {
        val rate = 384_000
        val n = 8192
        val tone = ShortArray(n) { (16000 * sin(2 * PI * 50_000 * it / rate)).toInt().toShort() }
        val frame = MelSpectrogram(rate, SpectrogramConfig.ULTRASOUND).process(tone).first()
        val peak = frame.indices.maxByOrNull { frame[it] }!!
        val expected = (50_000f / 192_000f * 256).toInt()
        assertTrue("Peak-Band $peak, erwartet ~$expected", kotlin.math.abs(peak - expected) <= 1)
    }

    @Test
    fun stridedProcessEqualsFilteredFrames() {
        val rate = 48_000
        val audio = ShortArray(rate * 2) { ((it * 31) % 4000 - 2000).toShort() }
        val all = MelSpectrogram(rate, SpectrogramConfig.BIRDS).process(audio)
        val stride = 3
        val expected = all.filterIndexed { i, _ -> i % stride == 0 }
        val mel = MelSpectrogram(rate, SpectrogramConfig.BIRDS)
        val got = mel.process(audio.copyOfRange(0, 50_000)) { it % stride == 0L } +
            mel.process(audio.copyOfRange(50_000, audio.size)) { it % stride == 0L }
        assertEquals(expected.size, got.size)
        for (i in expected.indices) assertArrayEquals(expected[i], got[i], 0f)
    }

    @Test
    fun zoomStreamingOnlyForLongUltrasound() {
        assertFalse(HighRateZoom.needsStreaming(48_000, 3600f))
        assertFalse(HighRateZoom.needsStreaming(384_000, 10f))
        assertTrue(HighRateZoom.needsStreaming(384_000, 30f))
        assertEquals(1, HighRateZoom.strideFor(1000, 512))
        assertEquals(3, HighRateZoom.strideFor(512L * 5000, 512, 2048))
    }

    @Test
    fun zoomStridedFramesBoundedAndReadInBlocks() {
        val rate = 384_000
        val total = rate * 30L
        var maxBlock = 0
        val frames = HighRateZoom.stridedFrames(rate, 0L, total, { a, b ->
            maxBlock = maxOf(maxBlock, (b - a).toInt())
            ShortArray((b - a).toInt()) { ((it * 13) % 200).toShort() }
        })
        assertTrue(frames.size <= HighRateZoom.TARGET_FRAMES)
        assertTrue(frames.size > HighRateZoom.TARGET_FRAMES - 5)
        assertEquals(256, frames.first().size)
        assertTrue(maxBlock <= HighRateZoom.BLOCK_SAMPLES)
    }
}
