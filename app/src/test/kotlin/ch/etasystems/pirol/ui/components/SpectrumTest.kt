package ch.etasystems.pirol.ui.components

import ch.etasystems.pirol.audio.dsp.SpectrumAnalyzer
import ch.etasystems.pirol.audio.dsp.SpectrumAverager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** T198: FFT-Spektrum (dBFS), Achsen-Mapping, Mittelung, Peak-Hold, Live-Zustand. */
class SpectrumTest {

    private fun sine(freq: Float, amp: Float, n: Int, sr: Int = 48000) =
        FloatArray(n) { (amp * sin(2.0 * PI * freq * it / sr)).toFloat() }

    @Test
    fun sinusVollaussteuerungIstNullDbfs() {
        for (n in SpectrumAnalyzer.FFT_SIZES) {
            val a = SpectrumAnalyzer(n)
            val spec = a.computeDbfs(sine(1000f, 1f, n))
            val peak = argMaxIn(spec, 0, spec.size - 1)
            val hz = SpectrumAnalyzer.binToHz(peak, n, 48000)
            assertEquals("n=$n Frequenz", 1000f, hz, 48000f / n)
            assertEquals("n=$n Pegel", 0f, spec[peak], 1f)
        }
    }

    @Test
    fun sinusZehntelIstMinusZwanzigDbfs() {
        val a = SpectrumAnalyzer(2048)
        val spec = a.computeDbfs(sine(1000f, 0.1f, 2048))
        val peak = argMaxIn(spec, 0, spec.size - 1)
        assertEquals(-20f, spec[peak], 1f)
        // weit weg vom Ton: tief unten
        assertTrue(spec[SpectrumAnalyzer.hzToBin(10000f, 2048, 48000)] < -80f)
    }

    @Test
    fun stilleLiefertUntergrenze() {
        val spec = SpectrumAnalyzer(512).computeDbfs(FloatArray(512))
        assertEquals(SpectrumAnalyzer.FLOOR_DBFS, spec[10], 0f)
        assertEquals(257, spec.size)
    }

    @Test
    fun binFrequenzUmrechnung() {
        assertEquals(23.4375f, SpectrumAnalyzer.binToHz(1, 2048, 48000), 1e-4f)
        assertEquals(24000f, SpectrumAnalyzer.binToHz(256, 512, 48000), 1e-3f)
        assertEquals(5.859375f, SpectrumAnalyzer.binToHz(1, 8192, 48000), 1e-5f)
        assertEquals(43, SpectrumAnalyzer.hzToBin(1000f, 2048, 48000))   // 42.67 -> 43
        assertEquals(171, SpectrumAnalyzer.hzToBin(1000f, 8192, 48000))  // 170.67 -> 171
        assertEquals(256, SpectrumAnalyzer.hzToBin(30000f, 512, 48000))  // begrenzt auf Nyquist
        assertEquals(0, SpectrumAnalyzer.hzToBin(-5f, 1024, 48000))
    }

    @Test
    fun achsenMappingKhzZuX() {
        // 0..12 kHz auf 34..634 px
        assertEquals(34f, spectrumHzToX(0f, 0f, 12000f, 34f, 600f), 1e-3f)
        assertEquals(334f, spectrumHzToX(6000f, 0f, 12000f, 34f, 600f), 1e-3f)
        assertEquals(634f, spectrumHzToX(12000f, 0f, 12000f, 34f, 600f), 1e-3f)
        assertEquals(6000f, spectrumXToHz(334f, 0f, 12000f, 34f, 600f), 1e-2f)
        assertEquals(12000f, spectrumXToHz(900f, 0f, 12000f, 34f, 600f), 0f)  // begrenzt
        // Hin und zurueck fuer jede FFT-Bin-Frequenz
        for (n in SpectrumAnalyzer.FFT_SIZES) {
            val hz = SpectrumAnalyzer.binToHz(n / 8, n, 48000)  // 3 kHz
            val x = spectrumHzToX(hz, 2000f, 8000f, 0f, 1000f)
            assertEquals(hz, spectrumXToHz(x, 2000f, 8000f, 0f, 1000f), 1e-2f)
            assertEquals(SpectrumAnalyzer.binToHz(SpectrumAnalyzer.hzToBin(hz, n, 48000), n, 48000), hz, 1e-3f)
        }
    }

    @Test
    fun frequenzGitter() {
        assertEquals(listOf(0f, 1000f, 2000f, 3000f, 4000f, 5000f, 6000f, 7000f, 8000f, 9000f, 10000f, 11000f, 12000f),
            spectrumFreqTicks(0f, 12000f))
        assertEquals(2000f, spectrumFreqStepHz(0f, 24000f), 0f)
        // enger Bereich: feineres Raster (200 Hz), 1800..3200 = 8 Linien
        assertEquals(200f, spectrumFreqStepHz(1800f, 3200f), 0f)
        val fine = spectrumFreqTicks(1800f, 3200f)
        assertEquals(8, fine.size)
        assertEquals(1800f, fine.first(), 0f)
        assertEquals(3200f, fine.last(), 0f)
        assertEquals(100f, spectrumFreqStepHz(3000f, 3800f), 0f)
        assertEquals("2", khzLabel(2000f))
        assertEquals("2.5", khzLabel(2500f))
        assertEquals("3.45 kHz  -23.1 dBFS", spectrumReadout(3450f, -23.12f))
    }

    @Test
    fun mittelungKonstantBleibtKonstant() {
        val avg = SpectrumAverager()
        repeat(20) { avg.update(floatArrayOf(-40f, -60f), 0.02f, 0.3f) }
        assertEquals(-40f, avg.avg[0], 1e-4f)
        assertEquals(-60f, avg.avg[1], 1e-4f)
    }

    @Test
    fun mittelungSprungExponentiell() {
        val tau = 0.3f
        val dt = 0.02f
        val avg = SpectrumAverager()
        avg.update(floatArrayOf(-80f), dt, tau)
        var t = 0f
        repeat(15) {
            avg.update(floatArrayOf(-20f), dt, tau)
            t += dt
        }
        // Soll: -20 + (-60) * exp(-t/tau)
        val expected = (-20.0 - 60.0 * exp(-t.toDouble() / tau)).toFloat()
        assertEquals(expected, avg.avg[0], 0.05f)
        // ohne Mittelung sofort am Ziel
        val off = SpectrumAverager()
        off.update(floatArrayOf(-80f), dt, 0f)
        off.update(floatArrayOf(-20f), dt, 0f)
        assertEquals(-20f, off.avg[0], 0f)
    }

    @Test
    fun peakHoldHaeltMaximumUndResetLeert() {
        val avg = SpectrumAverager()
        avg.update(floatArrayOf(-60f), 0.02f, 0f)
        avg.update(floatArrayOf(-10f), 0.02f, 0f)
        avg.update(floatArrayOf(-70f), 0.02f, 0f)
        assertEquals(-10f, avg.peak[0], 0f)
        avg.resetPeak()
        assertEquals(0, avg.peak.size)
        avg.update(floatArrayOf(-50f), 0.02f, 0f)
        assertEquals(-50f, avg.peak[0], 0f)
    }

    @Test
    fun liveZustandRechnetNurAktivUndNichtEingefroren() {
        val st = SpectrumState()
        st.configure(1024, 0f)
        val s = ShortArray(4800) { (16384 * sin(2.0 * PI * 3000.0 * it / 48000)).toInt().toShort() }
        assertFalse(st.active)
        st.setActive(true)
        st.append(s, 48000)
        assertTrue(st.hasData)
        val (a, _) = st.copyInto(null, null)!!
        val pk = argMaxIn(a, 0, a.size - 1)
        assertEquals(3000f, SpectrumAnalyzer.binToHz(pk, 1024, 48000), 48000f / 1024)
        assertEquals(-6f, a[pk], 1f)  // Amplitude 0.5
        val v = st.version
        st.frozen = true
        st.append(s, 48000)
        assertEquals(v, st.version)
        st.setActive(false)
        assertFalse(st.hasData)
        assertFalse(st.frozen)
    }
}
