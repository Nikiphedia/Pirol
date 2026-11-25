package ch.etasystems.pirol.audio.dsp

import ch.etasystems.pirol.audio.export.FrameSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** T294: Sprachband-Maske bei Ultraschall-Raten (FFT-Faltung), 48/96 kHz unveraendert direkt. */
class SpeechBandMaskerHighRateTest {

    @Test
    fun noiseTableSize() {
        assertEquals(1 shl 17, SpeechBandMasker.noiseTableSize(48_000))
        assertEquals(1 shl 17, SpeechBandMasker.noiseTableSize(96_000))
        assertEquals(1 shl 19, SpeechBandMasker.noiseTableSize(384_000))
        assertEquals(1 shl 19, SpeechBandMasker.noiseTableSize(500_000))
    }

    @Test
    fun directPathUpTo96k() {
        val h48 = SpeechBandMasker.designBandpass(48_000, SpeechBandMasker.LOW_HZ, SpeechBandMasker.HIGH_HZ)
        val h96 = SpeechBandMasker.designBandpass(96_000, SpeechBandMasker.LOW_HZ, SpeechBandMasker.HIGH_HZ)
        assertTrue(h48.size <= SpeechBandMasker.DIRECT_MAX_TAPS)
        assertTrue(h96.size <= SpeechBandMasker.DIRECT_MAX_TAPS)
        val h384 = SpeechBandMasker.designBandpass(384_000, SpeechBandMasker.LOW_HZ, SpeechBandMasker.HIGH_HZ)
        assertTrue(h384.size > SpeechBandMasker.DIRECT_MAX_TAPS)
    }

    @Test
    fun fftBandEqualsDirectConvolution() {
        val h = SpeechBandMasker.designBandpass(384_000, SpeechBandMasker.LOW_HZ, SpeechBandMasker.HIGH_HZ)
        val n = SpeechBandMasker.fftSizeFor(h.size)
        val re = FloatArray(n); val im = FloatArray(n)
        System.arraycopy(h, 0, re, 0, h.size)
        FFT.fft(re, im)
        val count = 40_000
        val ctx = FloatArray(count + h.size - 1) { ((it * 7919) % 20001 - 10000).toFloat() }
        val band = SpeechBandMasker.fftBand(ctx, count, h, re, im)
        for (i in intArrayOf(0, 1, 17, 12_345, 27_000, count - 1)) {
            var acc = 0.0
            for (k in h.indices) acc += h[k].toDouble() * ctx[i + k]
            assertEquals("i=$i", acc, band[i].toDouble(), 0.5)
        }
    }

    @Test
    fun masks1kHzKeeps40kHzAt384k() {
        val rate = 384_000
        val total = rate  // 1 s
        val audio = ShortArray(total) {
            (6000 * sin(2 * PI * 1000 * it / rate) + 6000 * sin(2 * PI * 40_000 * it / rate)).toInt().toShort()
        }
        val section = FrameSpan(rate / 4L, rate * 3L / 4)
        val reader: (Long, Long) -> ShortArray = { a, b ->
            ShortArray((b - a).toInt()) { i -> val idx = a + i; if (idx in 0 until total) audio[idx.toInt()] else 0 }
        }
        val masker = SpeechBandMasker(rate, listOf(section), total.toLong(), reader)
        val out = audio.copyOf()
        val t0 = System.nanoTime()
        masker.process(0L, out)
        val ms = (System.nanoTime() - t0) / 1_000_000
        // ausserhalb bitgenau
        for (i in 0 until section.startFrame.toInt()) assertEquals(audio[i], out[i])
        for (i in section.endFrame.toInt() until total) assertEquals(audio[i], out[i])
        // Mitte des Abschnitts: 1-kHz-Ton ersetzt (Rest nur noch Ersatzrauschen), 40-kHz-Anteil bleibt
        val a = rate * 2 / 5
        val b = rate * 3 / 5
        fun corr(freq: Int): Double {
            var c = 0.0
            for (i in a until b) c += out[i] * sin(2 * PI * freq * i / rate)
            return 2 * c / (b - a)
        }
        assertTrue("1 kHz Rest ${corr(1000)}", abs(corr(1000)) < 6000 * 0.1)
        assertTrue("40 kHz ${corr(40_000)}", abs(corr(40_000) - 6000) < 6000 * 0.05)
        assertTrue("Laufzeit $ms ms", ms < 20_000)
    }
}
