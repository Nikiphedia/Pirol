package ch.etasystems.pirol.data.repository

import ch.etasystems.pirol.ml.DetectionResult
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.FileWriter
import java.io.Writer
import java.nio.file.Files

/**
 * T93: Detektions-Persistenz im File-Modus. Stellt den Ablauf des SessionManagers nach:
 * Writer bleibt die ganze Session offen, replaceDetection() ersetzt die Datei dazwischen.
 */
class DetectionPersistenceTest {

    private val json = Json { prettyPrint = false }
    private lateinit var dir: File
    private lateinit var file: File

    @Before fun setUp() {
        dir = Files.createTempDirectory("t93").toFile()
        file = File(dir, "detections.jsonl")
    }

    @After fun tearDown() {
        dir.deleteRecursively()
    }

    private fun det(id: String, count: Int = 1) = DetectionResult(
        id = id, scientificName = "Parus major", commonName = "Kohlmeise",
        confidence = 0.9f, timestampMs = 1000L, chunkStartSec = 0f, chunkEndSec = 3f,
        sampleRate = 48000, count = count
    )

    private fun append(writer: Writer, d: DetectionResult) {
        writer.append(json.encodeToString(d)).append('\n')
        writer.flush()
    }

    private fun readDetections(): List<DetectionResult> =
        file.readLines().filter { it.isNotBlank() }.map { json.decodeFromString<DetectionResult>(it) }

    @Test fun appendReplaceAppend_keepsReplacementAndLaterAppend() {
        var writer: Writer? = FileWriter(file, true)
        append(writer!!, det("A"))
        writer = SessionManager.replaceDetectionInFile(file, det("A", count = 5), writer)
        append(writer!!, det("B"))
        writer.close()

        val result = readDetections()
        assertEquals(listOf("A", "B"), result.map { it.id })
        assertEquals(5, result[0].count)
    }

    @Test fun manyReplacesAndAppends_noLinesLost() {
        var writer: Writer? = FileWriter(file, true)
        for (i in 0 until 30) {
            append(writer!!, det("D$i"))
            if (i % 3 == 0) writer = SessionManager.replaceDetectionInFile(file, det("D$i", count = 7), writer)
        }
        writer!!.close()

        val result = readDetections()
        assertEquals((0 until 30).map { "D$it" }, result.map { it.id })
        assertEquals((0 until 30).map { if (it % 3 == 0) 7 else 1 }, result.map { it.count })
    }

    @Test fun writeFileReplacing_overwritesExistingFile() {
        val target = File(dir, "verifications.jsonl")
        target.writeText("alt\n")
        SessionManager.writeFileReplacing(target, "neu\n")
        assertEquals("neu\n", target.readText())
        assertEquals(false, File(dir, "verifications.jsonl.tmp").exists())
    }
}
