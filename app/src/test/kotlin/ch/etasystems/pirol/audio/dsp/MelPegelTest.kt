package ch.etasystems.pirol.audio.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * T199: Sonogramm in dBFS, gleiche Normierung wie [SpectrumAnalyzer] (Hann, Amplitude 2*|X|/Summe(w)).
 *
 * Toleranz +-3 dB: Ein Mel-Band summiert die Leistung mehrerer FFT-Bins mit Dreiecksgewicht
 * (Spitze 1). Ein Sinus verteilt sich mit Hann auf die Hauptkeule (rauschaequivalente Breite
 * 1.5 Bins, also bis +1.8 dB in Bandmitte); liegt er zwischen zwei Bandmitten, traegt das
 * staerkere Band noch mindestens das halbe Gewicht (-3 dB). Damit liegt das Frame-Maximum
 * in etwa -3..+2 dB um den Spektrum-Pegel.
 */
class MelPegelTest {

    private fun sineShort(amp: Double, hz: Double, sr: Int = 48000, sec: Double = 0.5): ShortArray =
        ShortArray((sr * sec).toInt()) { i -> (amp * 32767 * sin(2 * PI * hz * i / sr)).toInt().toShort() }

    private fun melPeak(amp: Double, hz: Double, config: SpectrogramConfig = SpectrogramConfig()): Float =
        MelSpectrogram(48000, config).process(sineShort(amp, hz)).maxOf { f -> f.max() }

    private fun spectrumPeak(amp: Double, hz: Double): Float {
        val samples = FloatArray(2048) { i -> (amp * sin(2 * PI * hz * i / 48000)).toFloat() }
        return SpectrumAnalyzer(2048).computeDbfs(samples).max()
    }

    @Test
    fun `Vollaussteuerung 2 kHz ergibt etwa 0 dBFS`() {
        val peak = melPeak(1.0, 2000.0)
        assertEquals("Spitze $peak dBFS", 0f, peak, 3f)
    }

    @Test
    fun `Amplitude 0_1 ergibt etwa -20 dBFS`() {
        val peak = melPeak(0.1, 2000.0)
        assertEquals("Spitze $peak dBFS", -20f, peak, 3f)
    }

    @Test
    fun `Sonogramm und Spektrum zeigen denselben Pegel`() {
        for (amp in doubleArrayOf(1.0, 0.1, 0.01)) {
            val mel = melPeak(amp, 2000.0)
            val spec = spectrumPeak(amp, 2000.0)
            assertEquals("amp=$amp mel=$mel spec=$spec", spec, mel, 3f)
            // auch mit der Voegel-Config (125-12000 Hz) des Live-Tabs
            val birds = melPeak(amp, 2000.0, SpectrogramConfig.BIRDS)
            assertEquals("BIRDS amp=$amp mel=$birds spec=$spec", spec, birds, 3f)
        }
    }

    @Test
    fun `leiser Ruf liegt deutlich ueber dem Boden`() {
        val peak = melPeak(0.01, 3000.0)  // -40 dBFS
        assertEquals("Spitze $peak dBFS", -40f, peak, 3f)
        assertTrue(peak > MEL_FLOOR_DBFS + 60f)
    }

    @Test
    fun `Stille landet am Boden`() {
        val frames = MelSpectrogram(48000).process(ShortArray(8192))
        assertEquals(MEL_FLOOR_DBFS, frames.maxOf { it.max() }, 0f)
    }
}
