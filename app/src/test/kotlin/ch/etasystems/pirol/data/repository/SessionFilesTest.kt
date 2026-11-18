package ch.etasystems.pirol.data.repository

import ch.etasystems.pirol.audio.export.ExcerptWav
import ch.etasystems.pirol.audio.export.FileSegment
import ch.etasystems.pirol.audio.export.SegmentSource
import ch.etasystems.pirol.audio.export.SegmentedRecording
import ch.etasystems.pirol.ml.DetectionResult
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.nio.file.Files

/** T131: SAF-Zugriff — reine Pfad-/Namens-/Lese-Logik (SAF selbst nur auf dem Geraet testbar). */
class SessionFilesTest {

    private lateinit var dir: File
    @Before fun setUp() { dir = Files.createTempDirectory("t131").toFile() }
    @After fun tearDown() { dir.deleteRecursively() }

    @Test fun safKey_mit_und_ohne_Tagesordner() {
        assertEquals("s1", SessionFilesLogic.safKey(null, "s1").name)
        assertEquals("2026-11-18", SessionFilesLogic.safKey("2026-11-18", "s1").parentFile!!.name)
        assertEquals(SessionFilesLogic.safKey("d", "s1").path, SessionFilesLogic.safKey("d", "s1").path)
    }

    @Test fun isSameSession_Pfad_oder_Docname() {
        assertTrue(SessionFilesLogic.isSameSession("/a/b/s1", null, "/a/b/s1", "s1"))
        // aktive SAF-Session: Pfad unbekannt, Name entscheidet
        assertTrue(SessionFilesLogic.isSameSession(null, "s1", "/saf/d/s1", "s1"))
        assertFalse(SessionFilesLogic.isSameSession(null, "s1", "/saf/d/s2", "s2"))
        assertFalse(SessionFilesLogic.isSameSession(null, null, "/a/s1", "s1"))
    }

    @Test fun dropListedNames_entfernt_Duplikate() {
        val l = listOf(File("/x/s1"), File("/x/s2"))
        assertEquals(listOf("s2"), SessionFilesLogic.dropListedNames(l, setOf("s1")).map { it.name })
    }

    @Test fun replaceDetectionInText_ersetzt_nur_gleiche_ID() {
        val a = det("a", 1); val b = det("b", 2)
        val json = Json { prettyPrint = false }
        val text = json.encodeToString(a) + "\n" + "kaputt\n" + json.encodeToString(b) + "\n"
        val out = SessionFilesLogic.replaceDetectionInText(text, b.copy(count = 7))
        val lines = out.lines().filter { it.isNotBlank() }
        assertEquals(3, lines.size)
        assertEquals("kaputt", lines[1])
        assertTrue(lines[2].contains("\"count\":7"))
        assertTrue(lines[0].contains("\"id\":\"a\""))
        assertTrue(out.endsWith("\n"))
    }

    @Test fun orderedSegmentNames_numerisch() {
        val n = listOf("recording-010.wav", "session.json", "recording.wav", "recording-002.wav")
        assertEquals(listOf("recording.wav", "recording-002.wav", "recording-010.wav"),
            SegmentedRecording.orderedSegmentNames(n))
    }

    @Test fun FileSessionFiles_lesen_schreiben_anhaengen() {
        val f = FileSessionFiles(dir)
        assertNull(f.readText("session.json"))
        f.writeReplacing("session.json", "{\"a\":1}")
        f.writeReplacing("session.json", "{\"a\":2}")
        assertEquals("{\"a\":2}", f.readText("session.json"))
        f.append("v.jsonl", "x\n"); f.append("v.jsonl", "y\n")
        assertEquals("x\ny\n", f.readText("v.jsonl"))
        assertTrue(f.exists("v.jsonl"))
    }

    /** Fake-SAF-Quelle: nur Stream-Zugriff (kein RandomAccess) wie ein nicht seekbarer Provider. */
    private class MemSegment(override val name: String, private val bytes: ByteArray) : SegmentSource {
        override fun length() = bytes.size.toLong()
        override fun readFully(pos: Long, dst: ByteArray) {
            System.arraycopy(bytes, pos.toInt(), dst, 0, dst.size)
        }
        override fun openStreamAt(pos: Long): InputStream =
            bytes.inputStream().also { it.skip(pos) }
        override val uriString: String get() = "content://fake/$name"
    }

    private fun wavBytes(from: Int, n: Int, rate: Int = 48000): ByteArray {
        val pcm = ShortArray(n) { (from + it).toShort() }
        val bb = java.nio.ByteBuffer.allocate(n * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        bb.asShortBuffer().put(pcm)
        return ExcerptWav.header(n, rate) + bb.array()
    }

    @Test fun fromSources_liest_ueber_Segmentgrenzen_wie_Files() {
        val rec = SegmentedRecording.fromSources(
            listOf(MemSegment("recording.wav", wavBytes(0, 2000)), MemSegment("recording-002.wav", wavBytes(2000, 1500)))
        )!!
        assertEquals(48000, rec.sampleRate)
        assertEquals(3500L, rec.totalFrames)
        assertNull(rec.firstFile)
        assertEquals("content://fake/recording.wav", rec.firstSource!!.uriString)
        val s = 1990f / 48000; val e = 2010f / 48000
        val pcm = rec.readRange(s, e)!!
        assertArrayEquals(ShortArray(20) { (1990 + it).toShort() }, pcm.samples)
    }

    @Test fun virtualWavStream_ueber_SAF_Quellen_mit_skip() {
        val rec = SegmentedRecording.fromSources(
            listOf(MemSegment("recording.wav", wavBytes(0, 100)), MemSegment("recording-002.wav", wavBytes(100, 100)))
        )!!
        val st = rec.openWavStream()
        // Header + Daten ab Frame 90 (ueberspannt Grenze)
        st.skip(44L + 90 * 2)
        val buf = ByteArray(40)
        var off = 0
        while (off < 40) { val r = st.read(buf, off, 40 - off); assertTrue(r > 0); off += r }
        val sh = java.nio.ByteBuffer.wrap(buf).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        assertEquals(90, sh.get(0).toInt())
        assertEquals(100, sh.get(10).toInt())
        assertEquals(109, sh.get(19).toInt())
    }

    @Test fun fromSources_leer_ist_null() {
        assertNull(SegmentedRecording.fromSources(emptyList()))
    }

    @Test fun FileSegment_openStreamAt_seekt() {
        val f = File(dir, "recording.wav"); f.writeBytes(wavBytes(0, 50))
        val st = FileSegment(f).openStreamAt(44L + 10 * 2)
        val b = ByteArray(2); st.read(b)
        assertEquals(10, java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN).short.toInt())
        st.close()
    }

    private fun det(id: String, count: Int): DetectionResult =
        Json { ignoreUnknownKeys = true }.decodeFromString(
            "{\"id\":\"$id\",\"scientificName\":\"Parus_major\",\"commonName\":\"Kohlmeise\"," +
                "\"confidence\":0.9,\"timestampMs\":1000,\"chunkStartSec\":0.0,\"chunkEndSec\":3.0,\"sampleRate\":48000,\"count\":$count}"
        )
}
