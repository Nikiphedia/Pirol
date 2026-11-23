package ch.etasystems.pirol.data.importer

import ch.etasystems.pirol.data.repository.DetectionUpdateLog
import ch.etasystems.pirol.data.repository.FileSessionFiles
import ch.etasystems.pirol.data.sync.createZip
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** T255: Import einer PIROL-Session aus ZIP (Kollision, Zusatzdatei, Pfade). */
class SessionPackageImportTest {

    private lateinit var src: File
    private lateinit var staging: File

    @Before fun setUp() {
        src = Files.createTempDirectory("t255src").toFile()
        staging = Files.createTempDirectory("t255stg").toFile()
    }

    @After fun tearDown() { src.deleteRecursively(); staging.deleteRecursively() }

    private val id = "2026-05-01T05-00-00_ab12cd"
    private fun sessionJson(extra: String = "") =
        """{"sessionId":"$id","startedAt":"2026-05-01T05:00:00+02:00","sampleRate":48000,"confidenceThreshold":0.5$extra}"""

    private fun line(id: String, count: Int) =
        """{"id":"$id","scientificName":"Parus major","commonName":"Kohlmeise","confidence":0.9,"timestampMs":1,"chunkStartSec":0.0,"chunkEndSec":3.0,"sampleRate":48000,"count":$count}"""

    private fun zipOf(entries: List<Pair<String, String>>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            entries.forEach { (n, c) -> z.putNextEntry(ZipEntry(n)); z.write(c.toByteArray()); z.closeEntry() }
        }
        return bos.toByteArray()
    }

    private fun extract(bytes: ByteArray): Int = SessionPackageLogic.extractZip(ByteArrayInputStream(bytes), { p ->
        File(staging, p).also { it.parentFile.mkdirs() }.outputStream()
    })

    @Test fun ownZip_roundtrip_withoutCollision() {
        File(src, "session.json").writeText(sessionJson())
        File(src, "detections.jsonl").writeText(line("a", 1) + "\n")
        File(src, "recording.wav").writeBytes(ByteArray(100) { it.toByte() })
        File(src, "memos").mkdirs(); File(src, "memos/a.wav").writeBytes(ByteArray(10))
        val zip = File(src.parentFile, "t255.zip").also { createZip(src, it) }
        try {
            val n = extract(zip.readBytes())
            assertEquals(4, n)
            assertTrue(File(staging, "memos/a.wav").exists())
            assertEquals(100L, File(staging, "recording.wav").length())
            val finalId = SessionPackageLogic.resolveSessionId(id, { false }, { "zzzzzz" })
            val meta = SessionPackageLogic.finalizeStaged(FileSessionFiles(staging), finalId)
            assertEquals(id, meta.sessionId)
            assertEquals("2026-05-01", SessionPackageLogic.dayDirOf(meta))
        } finally { zip.delete() }
    }

    @Test fun collision_newId_sessionJsonRewritten_unknownFieldsKept() {
        extract(zipOf(listOf("session.json" to sessionJson(""","futureField":{"x":1}"""))))
        val taken = setOf(id)
        val finalId = SessionPackageLogic.resolveSessionId(id, { it in taken }, { "new123" })
        assertEquals("2026-05-01T05-00-00_new123", finalId)
        val meta = SessionPackageLogic.finalizeStaged(FileSessionFiles(staging), finalId)
        assertEquals(finalId, meta.sessionId)
        val text = File(staging, "session.json").readText()
        assertTrue(text.contains("\"sessionId\": \"$finalId\""))
        assertTrue(text.contains("futureField"))
    }

    @Test fun updatesFile_mergedIntoDetections() {
        extract(zipOf(listOf(
            "session.json" to sessionJson(),
            "detections.jsonl" to line("a", 1) + "\n" + line("b", 1) + "\n",
            DetectionUpdateLog.FILE to line("a", 5) + "\n"
        )))
        SessionPackageLogic.finalizeStaged(FileSessionFiles(staging), id)
        assertFalse(File(staging, DetectionUpdateLog.FILE).exists())
        val lines = File(staging, "detections.jsonl").readLines().filter { it.isNotBlank() }
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("\"count\":5"))
    }

    @Test fun folderPrefix_stripped_and_unsafeSkipped() {
        val n = extract(zipOf(listOf(
            "$id/session.json" to sessionJson(),
            "$id/memos/x.wav" to "m",
            "$id/../evil.txt" to "x",
            "__MACOSX/$id/._session.json" to "x",
            "$id/detections.jsonl.new" to "x"   // Schattenkopie (T240)
        )))
        assertEquals(2, n)
        assertTrue(File(staging, "session.json").exists())
        assertTrue(File(staging, "memos/x.wav").exists())
        assertFalse(File(staging.parentFile, "evil.txt").exists())
    }

    @Test fun notASession() {
        extract(zipOf(listOf("readme.txt" to "hallo")))
        try {
            SessionPackageLogic.finalizeStaged(FileSessionFiles(staging), id)
            throw AssertionError("erwartet: NotASessionException")
        } catch (_: NotASessionException) { }
    }

    @Test fun safePath_rules() {
        assertEquals("a.wav", SessionPackageLogic.safePath("./a.wav"))
        assertEquals("memos/a.wav", SessionPackageLogic.safePath("memos\\a.wav"))
        assertNull(SessionPackageLogic.safePath("/etc/passwd"))
        assertNull(SessionPackageLogic.safePath("a/b/c.wav"))
        assertNull(SessionPackageLogic.safePath("C:/x.wav"))
        assertNull(SessionPackageLogic.safePath(".hidden"))
        assertNull(SessionPackageLogic.prefixOf("session.json"))
        assertNull(SessionPackageLogic.prefixOf("memos/a.wav"))
        assertEquals("x/", SessionPackageLogic.prefixOf("x/session.json"))
        assertEquals("a_b", SessionPackageLogic.sanitizeId("a/b"))
    }
}
