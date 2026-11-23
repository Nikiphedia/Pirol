package ch.etasystems.pirol.data.importer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** T255: WAV-Kopf/Metadaten streamend lesen, Mono-Mischung ueber Formate. */
class WavStreamTest {

    private fun chunk(id: String, body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(id.toByteArray(Charsets.US_ASCII))
        out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(body.size).array())
        out.write(body)
        if (body.size % 2 == 1) out.write(0)
        return out.toByteArray()
    }

    private fun fmt(tag: Int, ch: Int, rate: Int, bits: Int): ByteArray =
        ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(tag.toShort()).putShort(ch.toShort()).putInt(rate)
            .putInt(rate * ch * bits / 8).putShort((ch * bits / 8).toShort()).putShort(bits.toShort()).array()

    private fun wav(vararg chunks: ByteArray): ByteArray {
        val body = ByteArrayOutputStream().apply { write("WAVE".toByteArray()); chunks.forEach { write(it) } }.toByteArray()
        return chunk("RIFF", body)
    }

    private fun pcm16(vararg v: Int): ByteArray =
        ByteBuffer.allocate(v.size * 2).order(ByteOrder.LITTLE_ENDIAN).also { b -> v.forEach { b.putShort(it.toShort()) } }.array()

    private fun infoList(vararg kv: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream().apply { write("INFO".toByteArray()) }
        kv.forEach { (k, v) -> out.write(chunk(k, (v + "\u0000").toByteArray())) }
        return out.toByteArray()
    }

    @Test fun header_metadata_beforeAndAfterData() {
        val comment = "Recorded at 05:00:00 01/05/2024 (UTC+2) by AudioMoth"
        val bytes = wav(
            chunk("fmt ", fmt(1, 2, 44100, 16)),
            chunk("LIST", infoList("ICMT" to comment, "ICRD" to "2024-05-01")),
            chunk("data", pcm16(100, 300, -100, -300)),
            chunk("guan", "GUANO|Version: 1.0\nLoc Position: 47.1 8.2\n".toByteArray())
        )
        val info = WavInfoReader.read(ByteArrayInputStream(bytes), bytes.size.toLong())!!
        assertEquals(2, info.channels)
        assertEquals(44100, info.sampleRate)
        assertEquals(2L, info.frames)
        assertEquals(comment, info.comment)
        assertEquals("2024-05-01", info.creationDate)
        assertTrue(info.guano!!.contains("Loc Position"))
        assertTrue(info.supported)
    }

    @Test fun readStopsAtData_thenStereoMixedToMono() {
        val bytes = wav(chunk("fmt ", fmt(1, 2, 8000, 16)), chunk("data", pcm16(100, 300, -100, -300, 32767, 32767)),
            chunk("guan", "x".toByteArray()))
        val input = ByteArrayInputStream(bytes)
        val info = WavInfoReader.read(input, bytes.size.toLong(), readAfterData = false)!!
        val r = WavPcmReader(info, input)
        assertArrayEquals(shortArrayOf(200, -200), r.read(2))
        assertArrayEquals(shortArrayOf(32767), r.read(10))
        assertNull(r.read(10))  // guan-Chunk wird nicht als Audio gelesen
    }

    @Test fun unfinalizedHeader_readsToEnd() {
        val data = chunk("data", pcm16(1, 2, 3)).also { b -> for (i in 4..7) b[i] = 0 }  // Groesse 0
        val bytes = wav(chunk("fmt ", fmt(1, 1, 48000, 16)), data)
        val input = ByteArrayInputStream(bytes)
        val info = WavInfoReader.read(input, bytes.size.toLong(), readAfterData = false)!!
        assertArrayEquals(shortArrayOf(1, 2, 3), WavPcmReader(info, input).read(100))
    }

    @Test fun formats_24bit_float_8bit() {
        val p24 = byteArrayOf(0x00, 0x10, 0x00, 0x00, 0xF0.toByte(), 0xFF.toByte())  // 0x001000, -0x001000
        val s24 = ByteArrayInputStream(wav(chunk("fmt ", fmt(1, 1, 96000, 24)), chunk("data", p24)))
        val info24 = WavInfoReader.read(s24, -1, false)!!
        assertEquals(24, info24.bitsPerSample)
        assertArrayEquals(shortArrayOf(16, -16), WavPcmReader(info24, s24).read(10))

        val f = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putFloat(0.5f).putFloat(-2f).array()
        val sf = ByteArrayInputStream(wav(chunk("fmt ", fmt(3, 1, 48000, 32)), chunk("data", f)))
        val infoF = WavInfoReader.read(sf, -1, false)!!
        assertArrayEquals(shortArrayOf(16384, -32767), WavPcmReader(infoF, sf).read(10))

        val s8 = ByteArrayInputStream(wav(chunk("fmt ", fmt(1, 1, 8000, 8)), chunk("data", byteArrayOf(128.toByte(), 255.toByte()))))
        val info8 = WavInfoReader.read(s8, -1, false)!!
        assertArrayEquals(shortArrayOf(0, (127 * 256).toShort()), WavPcmReader(info8, s8).read(10))
    }

    @Test fun notWav_and_unsupported() {
        assertNull(WavInfoReader.read(ByteArrayInputStream("ID3xxxxxxxxxxxx".toByteArray())))
        val adpcm = WavInfoReader.read(ByteArrayInputStream(wav(chunk("fmt ", fmt(2, 1, 8000, 4)), chunk("data", ByteArray(4)))))!!
        assertFalse(adpcm.supported)
    }
}
