package ch.etasystems.pirol.data.importer

import ch.etasystems.pirol.audio.dsp.AudioResampler
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/** T255: Blockweises Resampling = Resampling in einem Stueck (gleiches Filter, gleiche Raender). */
class StreamResamplerTest {

    private fun signal(n: Int, rate: Int) =
        FloatArray(n) { (0.5 * sin(2 * PI * 1000.0 * it / rate) + 0.2 * sin(2 * PI * 3100.0 * it / rate)).toFloat() }

    private fun streamed(input: FloatArray, from: Int, to: Int, block: Int): FloatArray {
        val r = StreamResampler(from, to)
        var out = FloatArray(0)
        var off = 0
        while (off < input.size) {
            val n = minOf(block, input.size - off)
            out += r.push(input.copyOfRange(off, off + n))
            off += n
        }
        return out + r.finish()
    }

    private fun check(from: Int, to: Int) {
        val input = signal(from / 5, from)  // 0.2 s
        val whole = AudioResampler.resamplePolyphase(input, from, to)
        for (block in listOf(1, 37, 1000, 4096, input.size)) {
            val s = streamed(input, from, to, block)
            assertEquals("Laenge $from->$to Block $block", whole.size, s.size)
            for (i in whole.indices) assertEquals("Sample $i ($from->$to, Block $block)", whole[i], s[i], 1e-5f)
        }
    }

    @Test fun down_384k_to_48k() = check(384_000, 48_000)
    @Test fun up_44k1_to_48k() = check(44_100, 48_000)
    @Test fun down_96k_to_48k() = check(96_000, 48_000)

    @Test fun shorts_keepLevel() {
        val r = StreamResampler(96_000, 48_000)
        val out = r.push(ShortArray(9600) { 10000 }) + r.finishShorts()
        assertEquals(4800, out.size)
        // eingeschwungener Bereich in der Mitte: Gleichanteil bleibt erhalten
        assertEquals(10000.0, out[out.size / 2].toDouble(), 30.0)
    }
}
