package ch.etasystems.pirol.audio.dsp

import ch.etasystems.pirol.audio.export.ExcerptWav
import ch.etasystems.pirol.audio.export.FrameSpan
import ch.etasystems.pirol.audio.export.MaskedWavStream
import ch.etasystems.pirol.audio.export.SegmentedRecording
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * T261: Sprachband-Ersatz an synthetischem Signal: 1 kHz im Abschnitt >= 30 dB gedaempft, 6 kHz +/- 1 dB,
 * ausserhalb bitgenau, keine Spruenge an den Grenzen, Segmente ueber den Stream gleich wie am Stueck.
 */
class SpeechBandMaskerTest {

    private val sr = 48_000

    /** 1 kHz + 6 kHz (je Amplitude [amp]) + leises Rauschen */
    private fun signal(frames: Int, amp: Double = 0.25 * 32767): ShortArray {
        val rnd = java.util.Random(7)
        return ShortArray(frames) { n ->
            val t = n.toDouble() / sr
            (amp * sin(2 * PI * 1000 * t) + amp * sin(2 * PI * 6000 * t) + rnd.nextGaussian() * 30).toInt().toShort()
        }
    }

    private fun reader(x: ShortArray): (Long, Long) -> ShortArray = { from, to ->
        ShortArray((to - from).toInt()) { i -> val n = from + i; if (n in x.indices.first.toLong()..x.indices.last.toLong()) x[n.toInt()] else 0 }
    }

    /** Amplitude der Frequenz [f] in [from, to) (Projektion auf Sinus/Cosinus) */
    private fun amplitude(x: ShortArray, f: Double, from: Int, to: Int): Double {
        var re = 0.0; var im = 0.0
        for (n in from until to) {
            val ph = 2 * PI * f * n / sr
            re += x[n] * cos(ph); im += x[n] * sin(ph)
        }
        val len = (to - from).toDouble()
        return 2 * sqrt(re * re + im * im) / len
    }

    private fun db(a: Double, b: Double) = 20 * log10(a / b)

    @Test
    fun band_ersetzt_rest_bleibt() {
        val x = signal(6 * sr)
        val section = FrameSpan(2L * sr, 4L * sr)
        val masker = SpeechBandMasker(sr, listOf(section), x.size.toLong(), reader(x))
        val y = x.copyOf()
        // Blockweise wie im Stream (Bloecke queren die Abschnittsgrenzen)
        var pos = 0
        while (pos < y.size) {
            val n = minOf(10_000, y.size - pos)
            val block = y.copyOfRange(pos, pos + n)
            masker.process(pos.toLong(), block)
            System.arraycopy(block, 0, y, pos, n)
            pos += n
        }

        // Ausserhalb bitgenau
        for (n in 0 until 2 * sr) assertEquals("n=$n", x[n], y[n])
        for (n in 4 * sr until x.size) assertEquals("n=$n", x[n], y[n])

        // Innen (ohne Blenden): 1 kHz >= 30 dB weg, 6 kHz +/- 1 dB
        val a = (2.1 * sr).toInt(); val b = (3.9 * sr).toInt()
        val d1k = db(amplitude(y, 1000.0, a, b), amplitude(x, 1000.0, a, b))
        val d6k = db(amplitude(y, 6000.0, a, b), amplitude(x, 6000.0, a, b))
        println("T261_DSP taps=${masker.taps} 1kHz=${"%.1f".format(d1k)} dB 6kHz=${"%.2f".format(d6k)} dB")
        assertTrue("1 kHz nur $d1k dB", d1k <= -30.0)
        assertTrue("6 kHz $d6k dB", abs(d6k) <= 1.0)

        // Rauschen ersetzt das Band (nicht Stille): Band-Energie bleibt in der Groessenordnung
        val rmsY = sqrt((a until b).sumOf { y[it].toDouble() * y[it] } / (b - a))
        assertTrue("zu leise: $rmsY", rmsY > 0.25 * 32767 * 0.5)

        // Keine Spruenge an den Grenzen: Differenzen wie im Eingang
        val maxDiffX = (1 until x.size).maxOf { abs(x[it] - x[it - 1]) }
        for (edge in listOf(2 * sr, 4 * sr)) {
            for (n in edge - 50 until edge + 50) {
                val d = abs(y[n] - y[n - 1])
                assertTrue("Sprung $d bei $n", d <= maxDiffX * 1.1 + 50)
            }
        }
    }

    @Test
    fun ohne_abschnitt_unveraendert_und_tiefe_abtastrate() {
        val x = signal(sr)
        val m = SpeechBandMasker(sr, emptyList(), x.size.toLong(), reader(x))
        val y = x.copyOf(); m.process(0, y)
        assertArrayEquals(x, y)
        // 8 kHz Fremdaudio: obere Grenze unter Nyquist, kein Absturz
        val low = SpeechBandMasker(8000, listOf(FrameSpan(0, 8000)), 16_000, { f, t -> ShortArray((t - f).toInt()) })
        low.process(0, ShortArray(16_000))
    }

    @Test
    fun segmente_ueber_den_stream_wie_am_stueck() {
        val dir = Files.createTempDirectory("t261seg").toFile()
        try {
            val x = signal(6 * sr)
            val f1 = java.io.File(dir, "recording.wav")
            val f2 = java.io.File(dir, "recording-002.wav")
            ExcerptWav.write(f1, ExcerptWav.Pcm(x.copyOfRange(0, 3 * sr), sr))
            // ungerades Restbyte am Ende muss erhalten bleiben
            ExcerptWav.write(f2, ExcerptWav.Pcm(x.copyOfRange(3 * sr, 6 * sr), sr))
            f2.appendBytes(byteArrayOf(0x5A))
            val rec = SegmentedRecording.forSession(dir)!!
            val section = FrameSpan((2.5 * sr).toLong(), (4.5 * sr).toLong())  // quert die Segmentgrenze
            val recReader: (Long, Long) -> ShortArray = { from, to ->
                val out = ShortArray((to - from).toInt())
                val a = maxOf(0L, from); val b = minOf(rec.totalFrames, to)
                if (b > a) rec.readFrames(a, b)!!.samples.copyInto(out, (a - from).toInt())
                out
            }
            val masker = SpeechBandMasker(sr, listOf(section), rec.totalFrames, recReader)

            val direct = x.copyOf(); masker.process(0, direct)
            val streamed = ShortArray(x.size)
            for ((i, part) in rec.parts.withIndex()) {
                val bytes = ByteArrayOutputStream().also { out ->
                    MaskedWavStream(part.file!!.inputStream(), part.startFrame, masker).use { it.copyTo(out, 7777) }
                }.toByteArray()
                val orig = part.file!!.readBytes()
                assertEquals(orig.size, bytes.size)
                assertArrayEquals("Header", orig.copyOfRange(0, 44), bytes.copyOfRange(0, 44))
                if (i == 1) assertEquals(0x5A.toByte(), bytes.last())
                val n = part.frames.toInt()
                ByteBuffer.wrap(bytes, 44, n * 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    .get(streamed, part.startFrame.toInt(), n)
                if (i == 0) {
                    // vor dem Abschnitt bitgenau wie die Datei
                    assertArrayEquals(orig.copyOfRange(0, 44 + (2.5 * sr).toInt() * 2), bytes.copyOfRange(0, 44 + (2.5 * sr).toInt() * 2))
                }
            }
            assertArrayEquals(direct, streamed)
        } finally {
            dir.deleteRecursively()
        }
    }

    /** Rechenzeit auf dem Host: Sekunden Audio pro Sekunde CPU, ganz ausgeblendet (schlechtester Fall). */
    @Test
    fun rechenzeit() {
        val x = signal(30 * sr)
        val m = SpeechBandMasker(sr, listOf(FrameSpan(0, x.size.toLong())), x.size.toLong(), reader(x))
        val warm = x.copyOf(); m.process(0, warm, sr)  // JIT + Pegel
        val y = x.copyOf()
        val t0 = System.nanoTime()
        var pos = 0
        while (pos < y.size) {
            val n = minOf(32_768, y.size - pos)
            val block = y.copyOfRange(pos, pos + n)
            m.process(pos.toLong(), block)
            pos += n
        }
        val sec = (System.nanoTime() - t0) / 1e9
        println("T261_BENCH audio=30s cpu=${"%.2f".format(sec)}s ratio=${"%.1f".format(30 / sec)}x taps=${m.taps}")
        assertTrue(sec > 0)
    }
}
