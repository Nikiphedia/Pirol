package ch.etasystems.pirol.ui.components

import ch.etasystems.pirol.audio.dsp.MelFilterbank
import ch.etasystems.pirol.audio.dsp.MelSpectrogram
import ch.etasystems.pirol.audio.dsp.SpectrogramConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/** T200: Oszillogramm (Pegel, Skala, Uebersteuerung) und Bin<->Hz-Achse bis Nyquist. */
class WaveformLevelsTest {

    private fun sine(hz: Double, amp: Double, n: Int, rate: Int = 48000) =
        ShortArray(n) { i -> (sin(2.0 * PI * hz * i / rate) * amp * 32767.0).toInt().toShort() }

    @Test
    fun `Maximalpegel -20 dBFS ergibt Skala 0_1`() {
        assertEquals(0.1f, waveformFullScale(WaveformAmplitudeMode.MAX_LEVEL, 0.5f, -20f), 1e-4f)
        assertEquals(1f, waveformFullScale(WaveformAmplitudeMode.MAX_LEVEL, 0.5f, 0f), 1e-4f)
        assertEquals(0.01f, waveformFullScale(WaveformAmplitudeMode.MAX_LEVEL, 0.5f, -40f), 1e-5f)
        // feste Stufen und Auto bleiben
        assertEquals(1f, waveformFullScale(WaveformAmplitudeMode.FIXED_1, 0.001f, -20f), 0f)
        assertEquals(0.1f, waveformFullScale(WaveformAmplitudeMode.FIXED_01, 0.9f, -60f), 0f)
        assertEquals(0.01f, waveformFullScale(WaveformAmplitudeMode.FIXED_001, 0.9f, -60f), 0f)
        assertEquals(0.05f, waveformFullScale(WaveformAmplitudeMode.AUTO, 0.03f, -20f), 0f)
    }

    @Test
    fun `dBFS-Achse bildet minDb auf Null und maxDb auf den Rand ab`() {
        assertEquals(1f, dbfsDeflection(0.1f, -80f, -20f), 1e-4f)
        assertEquals(-1f, dbfsDeflection(-0.5f, -80f, -20f), 1e-4f)
        assertEquals(0.5f, dbfsDeflection(0.0031623f, -80f, -20f), 1e-3f)  // -50 dBFS = Mitte
        assertEquals(0f, dbfsDeflection(1e-6f, -80f, -20f), 0f)
        assertEquals(0.0031623f, dbfsDeflectionToAmplitude(0.5f, -80f, -20f), 1e-5f)
    }

    @Test
    fun `Fenster-Pegel Sinus Amplitude 1 - Peak 0 dBFS, RMS -3 dBFS, Uebersteuerung`() {
        val ws = WaveformState(maxBins = 200, binSize = 512)
        ws.append(sine(1000.0, 1.0, 48000))
        val n = ws.size
        val a = FloatArray(n); val b = FloatArray(n); val q = FloatArray(n); val r = FloatArray(n)
        ws.copyLast(n, a, b, q, r)
        val lv = waveformLevels(a, b, q, r, n, ws.binSize)
        assertEquals(0f, lv.peakDbfs, 0.05f)
        assertEquals(-3.01f, lv.rmsDbfs, 0.05f)
        assertTrue(lv.clipping)

        val quiet = WaveformState(maxBins = 200, binSize = 512)
        quiet.append(sine(1000.0, 0.1, 48000))
        quiet.copyLast(quiet.size, a, b, q, r)
        val lq = waveformLevels(a, b, q, r, quiet.size, quiet.binSize)
        assertEquals(-20f, lq.peakDbfs, 0.1f)
        assertFalse(lq.clipping)
    }

    @Test
    fun `Bandpass wirkt auf die Wellenform, Rohspitze bleibt fuer Uebersteuerung`() {
        val ws = WaveformState(maxBins = 200, binSize = 512)
        ws.setBand(2000f, 8000f)
        ws.append(sine(100.0, 1.0, 48000))  // lauter Brumm weit unter dem Durchlass
        // nur die zweite Haelfte (Einschwingen des Filters am Anfang ausgenommen)
        val n = ws.size / 2
        val a = FloatArray(n); val b = FloatArray(n); val q = FloatArray(n); val r = FloatArray(n)
        ws.copyLast(n, a, b, q, r)
        val lv = waveformLevels(a, b, q, r, n, ws.binSize)
        assertTrue("Peak ${lv.peakDbfs}", lv.peakDbfs < -40f)
        assertTrue(lv.clipping)  // Rohsignal ungefiltert
    }

    @Test
    fun `Freeze haelt den Puffer an`() {
        val ws = WaveformState(maxBins = 10, binSize = 4)
        ws.append(ShortArray(8))
        ws.frozen = true
        ws.append(ShortArray(8))
        assertEquals(2, ws.size)
    }

    /**
     * Bin <-> Hz: Ein Sinus landet im Sonogramm (Live, Analyse, Export: dieselbe Config fuer Mel,
     * Bin-Zuschnitt und Achse) auf der Hoehe, die die Frequenzachse fuer seine Frequenz zeigt.
     */
    @Test
    fun `Sinus landet auf der richtigen Hoehe der Frequenzachse`() {
        for (rate in intArrayOf(48000, 44100)) {
            val config = SpectrogramConfig.BIRDS.resolved(rate)
            for ((lo, hi) in listOf(0f to 24000f, 1000f to 12000f, 125f to 20000f)) {
                val axis = config.forDisplay(lo, hi)
                val bins = displayBinRange(config, lo, hi)
                for (hz in doubleArrayOf(1500.0, 4000.0, 9000.0, 11000.0)) {
                    if (hz < axis.fMin || hz > axis.fMax) continue
                    val f = MelSpectrogram(rate, config).process(sine(hz, 0.5, 8192, rate))[0]
                    var best = 0
                    for (i in f.indices) if (f[i] > f[best]) best = i
                    assertTrue("rate=$rate hz=$hz bin=$best", best in bins)
                    // Hoehe in der Anzeige (0 = unten) und Hz laut Achse an dieser Hoehe
                    val frac = (best - bins.first + 0.5f) / (bins.last - bins.first + 1)
                    val melLo = MelFilterbank.hzToMel(axis.fMin)
                    val melHi = MelFilterbank.hzToMel(axis.fMax)
                    val axisHz = MelFilterbank.melToHz(melLo + frac * (melHi - melLo))
                    assertEquals("rate=$rate band=$lo..$hi hz=$hz achse=$axisHz", hz.toFloat(), axisHz, (hz * 0.04).toFloat())
                    assertEquals(frac, frequencyFraction(axisHz, axis.fMin, axis.fMax), 1e-3f)
                }
            }
        }
    }
}
