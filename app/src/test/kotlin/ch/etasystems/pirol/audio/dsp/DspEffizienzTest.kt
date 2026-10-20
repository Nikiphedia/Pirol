package ch.etasystems.pirol.audio.dsp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/**
 * Regressionstests fuer die Effizienz-Optimierungen (T61):
 * - FFT mit vorberechneten Twiddle-Faktoren liefert korrekte DFT
 * - MelSpectrogram.process() liefert unabhaengig von der Chunk-Groesse dieselben Frames
 *   (Lese-Offset statt Buffer-Shift pro Frame)
 * - Quickselect liefert exakt dieselben Perzentile wie eine Vollsortierung
 * - Kombiniertes Normalisieren + Resampling von ShortArray ist bitidentisch
 */
class DspEffizienzTest {

    @Test
    fun `FFT entspricht naiver DFT`() {
        val n = 64
        val rnd = Random(7)
        val x = FloatArray(n) { rnd.nextFloat() * 2f - 1f }
        val re = x.copyOf()
        val im = FloatArray(n)
        FFT.fft(re, im)
        for (k in 0 until n) {
            var sr = 0.0
            var si = 0.0
            for (t in 0 until n) {
                sr += x[t] * cos(-2.0 * PI * k * t / n)
                si += x[t] * sin(-2.0 * PI * k * t / n)
            }
            assertEquals(sr, re[k].toDouble(), 1e-4)
            assertEquals(si, im[k].toDouble(), 1e-4)
        }
    }

    @Test
    fun `MelSpectrogram ist unabhaengig von der Chunk-Groesse`() {
        val rate = 48000
        val audio = ShortArray(rate * 2 + 777) { i ->
            (sin(2.0 * PI * 3000.0 * i / rate) * 12000.0).toInt().toShort()
        }
        val reference = MelSpectrogram(rate, SpectrogramConfig.BIRDS).process(audio)
        for (chunk in listOf(4800, 1000, 333)) {
            val mel = MelSpectrogram(rate, SpectrogramConfig.BIRDS)
            val frames = ArrayList<FloatArray>()
            var offset = 0
            while (offset < audio.size) {
                val end = minOf(audio.size, offset + chunk)
                frames += mel.process(audio.copyOfRange(offset, end))
                offset = end
            }
            assertEquals(reference.size, frames.size)
            for (i in reference.indices) assertArrayEquals(reference[i], frames[i], 0f)
        }
    }

    @Test
    fun `Quickselect liefert dieselben Perzentile wie Sortierung`() {
        val rnd = Random(3)
        repeat(200) { trial ->
            val n = rnd.nextInt(1, 2000)
            // Viele Duplikate (Clipping bei minDb) als Worst-Case fuer Partitionierung
            val arr = FloatArray(n) { if (rnd.nextInt(4) == 0) -80f else rnd.nextInt(-800, 1) / 10f }
            if (trial % 50 == 0) arr.fill(-80f)
            val sorted = arr.copyOf().also { it.sort() }
            for (k in listOf(0, n / 50, n / 2, (n * 0.98f).toInt().coerceAtMost(n - 1), n - 1)) {
                assertEquals(sorted[k], DynamicRangeMapper.selectKth(arr.copyOf(), n, k), 0f)
            }
        }
    }

    @Test
    fun `computeStatic liefert p2 und p98`() {
        val rnd = Random(5)
        val frames = List(300) { FloatArray(128) { rnd.nextFloat() * -80f } }
        val flat = frames.flatMap { it.asList() }.toFloatArray().also { it.sort() }
        val n = flat.size
        val (lo, hi) = DynamicRangeMapper.computeStatic(frames)
        assertEquals(flat[(n * 0.02f).toInt()], lo, 0f)
        assertEquals(flat[(n * 0.98f).toInt()], hi, 0f)
    }

    @Test
    fun `Resampling von ShortArray ist bitidentisch zu Normalisieren plus Resampling`() {
        val rnd = Random(11)
        val pcm = ShortArray(48000) { rnd.nextInt(-32768, 32768).toShort() }
        for ((from, to) in listOf(48000 to 32000, 96000 to 32000, 32000 to 32000)) {
            val expected = AudioResampler.resample(FloatArray(pcm.size) { pcm[it] / 32768.0f }, from, to)
            val actual = AudioResampler.resample(pcm, from, to)
            assertTrue("$from->$to", expected.contentEquals(actual))
        }
    }

    @Test
    fun `FFT Twiddle-Cache funktioniert fuer verschiedene Groessen`() {
        for (n in listOf(2, 16, 1024, 2048)) {
            val re = FloatArray(n).also { it[0] = 1f } // Impuls → flaches Spektrum
            val im = FloatArray(n)
            FFT.fft(re, im)
            var err = 0f
            for (k in 0 until n) err = max(err, max(abs(re[k] - 1f), abs(im[k])))
            assertTrue("n=$n err=$err", err < 1e-6f)
        }
    }

    @Test
    fun `Uebersicht kurzer Aufnahme entspricht den vollen Frames`() {
        val rate = 48000
        val pcm = ShortArray(rate * 5) { i -> (sin(2.0 * PI * 2500.0 * i / rate) * 9000.0).toInt().toShort() }
        val full = MelSpectrogram(rate, SpectrogramConfig()).process(pcm)
        val overview = SpectrogramOverview.compute(pcmStream(pcm), pcm.size.toLong(), rate, SpectrogramConfig(), 2048)
        assertEquals(full.size, overview.size)
        for (i in full.indices) assertArrayEquals(full[i], overview[i], 0f)
    }

    @Test
    fun `Uebersicht langer Aufnahme ist Max-Pooling auf maxColumns`() {
        val rate = 48000
        val rnd = Random(9)
        val pcm = ShortArray(rate * 40) { (rnd.nextFloat() * 6000f - 3000f).toInt().toShort() }
        val cols = 512
        val config = SpectrogramOverview.overviewConfig(SpectrogramConfig(), pcm.size.toLong(), cols)
        val full = MelSpectrogram(rate, config).process(pcm)
        val expected = Array(cols) { FloatArray(128) { Float.NEGATIVE_INFINITY } }
        for (f in full.indices) {
            val c = (f.toLong() * cols / full.size).toInt()
            for (b in 0 until 128) expected[c][b] = max(expected[c][b], full[f][b])
        }
        val overview = SpectrogramOverview.compute(pcmStream(pcm), pcm.size.toLong(), rate, SpectrogramConfig(), cols)
        assertEquals(cols, overview.size)
        for (i in 0 until cols) assertArrayEquals(expected[i], overview[i], 0f)
    }

    /** 16-bit LE PCM als Stream */
    private fun pcmStream(pcm: ShortArray): java.io.InputStream {
        val bytes = java.nio.ByteBuffer.allocate(pcm.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        bytes.asShortBuffer().put(pcm)
        return java.io.ByteArrayInputStream(bytes.array())
    }
}
