package ch.etasystems.pirol.data.sync

import ch.etasystems.pirol.data.repository.DetectionUpdateLog
import ch.etasystems.pirol.data.repository.FileSessionFiles
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile

/** T253: ZIP mit zusammengefuehrten Detektions-Updates, Zusatzdatei nie im ZIP. */
class SessionZipTest {

    private lateinit var dir: File
    private lateinit var zip: File

    @Before fun setUp() {
        dir = Files.createTempDirectory("t253").toFile()
        zip = File.createTempFile("t253", ".zip")
    }

    @After fun tearDown() { dir.deleteRecursively(); zip.delete() }

    private fun line(id: String, count: Int) =
        """{"id":"$id","scientificName":"Parus major","commonName":"Kohlmeise","confidence":0.9,"timestampMs":1,"chunkStartSec":0.0,"chunkEndSec":3.0,"sampleRate":48000,"count":$count}"""

    private fun entries(): Map<String, String> = ZipFile(zip).use { z ->
        z.entries().asSequence().associate { it.name to z.getInputStream(it).readBytes().toString(Charsets.UTF_8) }
    }

    @Test
    fun updatesFileNeverInZip_mergedTextUsed() {
        File(dir, "session.json").writeText("{}")
        File(dir, "detections.jsonl").writeText(line("a", 1) + "\n" + line("b", 1) + "\n")
        File(dir, DetectionUpdateLog.FILE).writeText(line("a", 7) + "\n")
        // Aktive Session: zusammengefuehrter Stand, Dateien auf der Platte bleiben unveraendert
        val merged = DetectionUpdateLog.mergedText(FileSessionFiles(dir))!!
        createZip(dir, zip, merged)
        val e = entries()
        assertFalse(e.containsKey(DetectionUpdateLog.FILE))
        assertEquals(merged, e["detections.jsonl"])
        assertTrue(e["detections.jsonl"]!!.contains("\"count\":7"))
        assertTrue(File(dir, DetectionUpdateLog.FILE).exists())
        assertFalse(File(dir, "detections.jsonl").readText().contains("\"count\":7"))
    }

    @Test
    fun consolidatedSession_zipHasMergedFile() {
        File(dir, "detections.jsonl").writeText(line("a", 1) + "\n")
        File(dir, DetectionUpdateLog.FILE).writeText(line("a", 4) + "\n")
        DetectionUpdateLog.consolidate(FileSessionFiles(dir))
        createZip(dir, zip, null)
        val e = entries()
        assertFalse(e.containsKey(DetectionUpdateLog.FILE))
        assertTrue(e["detections.jsonl"]!!.contains("\"count\":4"))
    }

    @Test
    fun withoutOverride_fileAsIs() {
        File(dir, "detections.jsonl").writeText(line("a", 1) + "\n")
        createZip(dir, zip)
        assertTrue(entries()["detections.jsonl"]!!.contains("\"count\":1"))
    }
}
