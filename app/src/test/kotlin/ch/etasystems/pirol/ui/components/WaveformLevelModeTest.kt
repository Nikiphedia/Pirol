package ch.etasystems.pirol.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** T201: dBFS-Bereich der Oszillogramm-Achse (wie FFT, wie Sonogramm) und Abbildung dBFS -> y. */
class WaveformLevelModeTest {

    private val base = SpectrogramDisplaySettings(autoContrast = true, minDb = -80f, maxDb = -20f, ceilingDb = 0f)

    @Test
    fun `wie FFT nutzt den gemeinsamen Bereich`() {
        val (lo, hi) = waveformDbRange(WaveformLevelMode.FOLLOW_FFT, base, autoRange = -60f to -30f)
        assertEquals(-80f, lo, 0f)
        assertEquals(-20f, hi, 0f)
    }

    @Test
    fun `wie FFT hat Mindestspanne`() {
        val s = base.copy(minDb = -30f, maxDb = -30f)
        val (lo, hi) = waveformDbRange(WaveformLevelMode.FOLLOW_FFT, s, null)
        assertEquals(-30f, lo, 0f)
        assertEquals(-20f, hi, 0f)
    }

    @Test
    fun `wie Sono folgt dem Auto-Kontrast-Bereich`() {
        assertEquals(-70f to -35f, waveformDbRange(WaveformLevelMode.FOLLOW_SONO, base, -70f to -35f))
        // geaenderter Auto-Bereich -> geaenderte Achse
        assertEquals(-55f to -25f, waveformDbRange(WaveformLevelMode.FOLLOW_SONO, base, -55f to -25f))
    }

    @Test
    fun `wie Sono ohne Auto-Kontrast = fester Bereich, Ceiling gilt`() {
        val fixed = base.copy(autoContrast = false)
        assertEquals(-80f to -20f, waveformDbRange(WaveformLevelMode.FOLLOW_SONO, fixed, -70f to -35f))
        val capped = fixed.copy(ceilingDb = -40f)
        assertEquals(-80f to -40f, waveformDbRange(WaveformLevelMode.FOLLOW_SONO, capped, null))
        // Ceiling darf die Mindestspanne nicht unterschreiten
        val tight = fixed.copy(ceilingDb = -78f)
        val (lo, hi) = waveformDbRange(WaveformLevelMode.FOLLOW_SONO, tight, null)
        assertTrue(hi - lo >= MIN_DISPLAY_SPAN_DB)
    }

    @Test
    fun `wie Sono ohne Mapper und nicht endlicher Bereich faellt zurueck`() {
        val (lo, hi) = waveformDbRange(WaveformLevelMode.FOLLOW_SONO, base, null)
        assertEquals(-80f, lo, 0f)
        assertTrue(hi > lo)
        assertEquals(-80f to -20f, waveformDbRange(WaveformLevelMode.FOLLOW_SONO, base, Float.NaN to Float.NaN))
    }

    @Test
    fun `dBFS nach Auslenkung folgt dem Sono-Bereich`() {
        // Amplitude -40 dBFS (0.01): im FFT-Bereich -80..-20 bei 2/3, im Sono-Bereich -60..-20 bei 1/2
        val a = 0.01f
        val fft = waveformDbRange(WaveformLevelMode.FOLLOW_FFT, base, -60f to -20f)
        val sono = waveformDbRange(WaveformLevelMode.FOLLOW_SONO, base, -60f to -20f)
        assertEquals(40f / 60f, dbfsDeflection(a, fft.first, fft.second), 1e-3f)
        assertEquals(0.5f, dbfsDeflection(a, sono.first, sono.second), 1e-3f)
        // Sono-Bereich aendert sich -> Auslenkung aendert sich
        val sono2 = waveformDbRange(WaveformLevelMode.FOLLOW_SONO, base, -50f to -20f)
        assertEquals(10f / 30f, dbfsDeflection(a, sono2.first, sono2.second), 1e-3f)
        // Vorzeichen bleibt
        assertEquals(-0.5f, dbfsDeflection(-a, sono.first, sono.second), 1e-3f)
    }
}
