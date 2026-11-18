package ch.etasystems.pirol.data.repository

import ch.etasystems.pirol.audio.export.ExcerptWav
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** T123: Artnamen, Dateinamen und WAV-Import (reine Logik). */
class ReferenceImportTest {

    @Test fun speciesKey_normalisiert() {
        assertEquals("Turdus_merula", ReferenceNaming.speciesKey(" Turdus  merula "))
        assertEquals("Turdus_merula", ReferenceNaming.speciesKey("Turdus_merula"))
        assertEquals("Unbekannt", ReferenceNaming.speciesKey("  "))
    }

    @Test fun nextWavFileName_ueberspringtBelegte() {
        assertEquals("ref_001_Parus_major.wav", ReferenceNaming.nextWavFileName("Parus_major", emptyList()))
        val existing = listOf("ref_002_Parus_major.wav")
        assertEquals("ref_003_Parus_major.wav", ReferenceNaming.nextWavFileName("Parus_major", existing))
    }

    @Test fun extensionOf() {
        assertEquals("flac", ReferenceNaming.extensionOf("Song.FLAC"))
        assertEquals("", ReferenceNaming.extensionOf("ohne"))
        assertEquals("", ReferenceNaming.extensionOf(null))
    }

    @Test fun downmix_stereo() {
        val mono = WavParser.downmixToMono(shortArrayOf(100, 300, -100, -300), 2)
        assertEquals(listOf<Short>(200, -200), mono.toList())
    }

    @Test fun parse_roundtrip_mono() {
        val pcm = ExcerptWav.Pcm(shortArrayOf(1, -2, 3, 4), 48000)
        val tmp = java.io.File.createTempFile("t123", ".wav")
        try {
            ExcerptWav.write(tmp, pcm)
            val parsed = WavParser.parse(tmp.readBytes())
            assertNotNull(parsed)
            assertEquals(48000, parsed!!.sampleRate)
            assertEquals(pcm.samples.toList(), parsed.samples.toList())
        } finally { tmp.delete() }
    }

    @Test fun parse_stereo_mitZusatzChunk() {
        val data = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(10).putShort(20).putShort(30).putShort(50).array()
        val bb = ByteBuffer.allocate(12 + 8 + 16 + 8 + 4 + 8 + 8).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()).putInt(0).put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(2).putInt(22050)
            .putInt(22050 * 4).putShort(4).putShort(16)
        bb.put("LIST".toByteArray()).putInt(4).put(byteArrayOf(0, 0, 0, 0))
        bb.put("data".toByteArray()).putInt(8).put(data)
        val parsed = WavParser.parse(bb.array())
        assertNotNull(parsed)
        assertEquals(22050, parsed!!.sampleRate)
        assertEquals(listOf<Short>(15, 40), parsed.samples.toList())
    }

    @Test fun parse_ungueltig() {
        assertNull(WavParser.parse(ByteArray(5)))
        assertNull(WavParser.parse(ByteArray(64)))
    }
}
