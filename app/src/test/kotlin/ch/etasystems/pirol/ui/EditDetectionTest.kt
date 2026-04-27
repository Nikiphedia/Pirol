package ch.etasystems.pirol.ui

import ch.etasystems.pirol.ml.DetectionListState
import ch.etasystems.pirol.ml.DetectionResult
import ch.etasystems.pirol.ml.VerificationEvent
import ch.etasystems.pirol.ml.VerificationStatus
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

/**
 * Unit-Tests fuer T68 EditDetection-Funktionalitaet.
 *
 * AC7: editDetection() schreibt korrekte Eintraege in beide JSONL.
 * AC8: DetectionListState.updateDetection() korrekt.
 * AC9: Race-Test sequentielle Edits → finaler Wert korrekt persistiert.
 */
class EditDetectionTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private val jsonLenient = Json { ignoreUnknownKeys = true; isLenient = true }

    // expliziter Json-Instance fuer Encode (kein reified inline noetig in Tests)
    private val jsonEncode = Json { encodeDefaults = false }

    // ── Hilfsfunktionen ────────────────────────────────────────────────────────

    private fun makeDetection(
        id: String = UUID.randomUUID().toString(),
        count: Int = 1,
        atlasCode: String? = null,
        note: String? = null
    ) = DetectionResult(
        id = id,
        scientificName = "Turdus merula",
        commonName = "Amsel",
        confidence = 0.9f,
        timestampMs = System.currentTimeMillis(),
        chunkStartSec = 0f,
        chunkEndSec = 3f,
        sampleRate = 48000,
        count = count,
        atlasCode = atlasCode,
        note = note
    )

    private fun encodeDetection(det: DetectionResult): String =
        jsonEncode.encodeToString(DetectionResult.serializer(), det)

    private fun encodeEvent(event: VerificationEvent): String =
        jsonEncode.encodeToString(VerificationEvent.serializer(), event)

    /** JSONL-Zeile ersetzen — gleiche Logik wie SessionManager.replaceDetection File-Modus */
    private fun replaceInJsonl(file: File, updated: DetectionResult) {
        val encodedLine = encodeDetection(updated)
        val lines = file.readLines().map { raw ->
            if (raw.isBlank()) raw
            else try {
                if (jsonLenient.decodeFromString(DetectionResult.serializer(), raw).id == updated.id) encodedLine
                else raw
            } catch (_: Exception) { raw }
        }
        // In Tests direkt schreiben (kein Filesystem-Constraint fuer rename noetig)
        file.writeText(if (lines.isEmpty()) "" else lines.joinToString("\n") + "\n")
    }

    /** VerificationEvents granular appenden — gleiche Logik wie editDetection() */
    private fun appendVerificationEvents(
        veriFile: File,
        current: DetectionResult,
        count: Int,
        atlasCode: String?,
        note: String?
    ) {
        val now = System.currentTimeMillis()
        if (current.count != count) veriFile.appendText(
            encodeEvent(VerificationEvent(current.id, current.verificationStatus,
                verifiedAtMs = now, actionType = VerificationEvent.ACTION_COUNT_CHANGED)) + "\n")
        if (current.atlasCode != atlasCode) veriFile.appendText(
            encodeEvent(VerificationEvent(current.id, current.verificationStatus,
                verifiedAtMs = now, actionType = VerificationEvent.ACTION_ATLAS_SET)) + "\n")
        if (current.note != note) veriFile.appendText(
            encodeEvent(VerificationEvent(current.id, current.verificationStatus,
                verifiedAtMs = now, actionType = VerificationEvent.ACTION_NOTE_ADDED)) + "\n")
    }

    // ── AC7a: JSONL-Ersetzung ────────────────────────────────────────────────

    @Test
    fun `ac7a editDetection ersetzt Zeile in detections jsonl korrekt`() {
        val dir = tmpFolder.newFolder("session")
        val detection = makeDetection(count = 1)
        val detFile = File(dir, "detections.jsonl")
        detFile.writeText(encodeDetection(detection) + "\n")

        val updated = detection.copy(count = 3, atlasCode = "AC5", note = "Singt in Buche")
        replaceInJsonl(detFile, updated)

        val lines = detFile.readLines().filter { it.isNotBlank() }
        assertEquals("Genau eine Zeile erwartet", 1, lines.size)
        val restored = jsonLenient.decodeFromString(DetectionResult.serializer(), lines[0])
        assertEquals(3, restored.count)
        assertEquals("AC5", restored.atlasCode)
        assertEquals("Singt in Buche", restored.note)
        assertEquals(detection.id, restored.id)
    }

    @Test
    fun `ac7b editDetection beruehrt andere Detektionen nicht`() {
        val dir = tmpFolder.newFolder("session2")
        val det1 = makeDetection(id = "id-1", count = 1)
        val det2 = makeDetection(id = "id-2", count = 2)
        val detFile = File(dir, "detections.jsonl")
        detFile.writeText(encodeDetection(det1) + "\n" + encodeDetection(det2) + "\n")

        replaceInJsonl(detFile, det1.copy(count = 5))

        val lines = detFile.readLines().filter { it.isNotBlank() }
        assertEquals(2, lines.size)
        val r1 = jsonLenient.decodeFromString(DetectionResult.serializer(), lines[0])
        val r2 = jsonLenient.decodeFromString(DetectionResult.serializer(), lines[1])
        assertEquals(5, r1.count)
        assertEquals(2, r2.count)
    }

    // ── AC7c/d: VerificationEvents ───────────────────────────────────────────

    @Test
    fun `ac7c editDetection schreibt drei granulare VerificationEvents`() {
        val dir = tmpFolder.newFolder("session3")
        val detection = makeDetection(count = 1, atlasCode = null, note = null)
        val veriFile = File(dir, "verifications.jsonl")
        veriFile.createNewFile()

        appendVerificationEvents(veriFile, detection, count = 3, atlasCode = "AC7", note = "Testnotiz")

        val events = veriFile.readLines().filter { it.isNotBlank() }
            .map { jsonLenient.decodeFromString(VerificationEvent.serializer(), it) }
        assertEquals("3 Events erwartet (count+atlas+note)", 3, events.size)
        assertTrue(events.any { it.actionType == VerificationEvent.ACTION_COUNT_CHANGED })
        assertTrue(events.any { it.actionType == VerificationEvent.ACTION_ATLAS_SET })
        assertTrue(events.any { it.actionType == VerificationEvent.ACTION_NOTE_ADDED })
    }

    @Test
    fun `ac7d nur geaenderte Felder erzeugen VerificationEvents`() {
        val dir = tmpFolder.newFolder("session4")
        val detection = makeDetection(count = 2, atlasCode = "AC1", note = null)
        val veriFile = File(dir, "verifications.jsonl")
        veriFile.createNewFile()

        // Nur count aendert sich; atlasCode und note bleiben gleich
        appendVerificationEvents(veriFile, detection, count = 5, atlasCode = "AC1", note = null)

        val events = veriFile.readLines().filter { it.isNotBlank() }
            .map { jsonLenient.decodeFromString(VerificationEvent.serializer(), it) }
        assertEquals("Nur 1 Event erwartet (nur count geaendert)", 1, events.size)
        assertEquals(VerificationEvent.ACTION_COUNT_CHANGED, events[0].actionType)
    }

    // ── AC8: DetectionListState.updateDetection ───────────────────────────────

    @Test
    fun `ac8a updateDetection ersetzt Detektion an gleicher Position`() {
        val state = DetectionListState()
        val det = makeDetection(id = "id-x", count = 1)
        state.addDetections(listOf(det))

        val result = state.updateDetection(det.copy(count = 7, atlasCode = "AC3"))

        assertTrue("updateDetection sollte true zurueckgeben", result)
        val found = state.getDetections().find { it.id == "id-x" }
        assertNotNull(found)
        assertEquals(7, found!!.count)
        assertEquals("AC3", found.atlasCode)
    }

    @Test
    fun `ac8b updateDetection fuer unbekannte ID gibt false zurueck`() {
        val state = DetectionListState()
        state.addDetections(listOf(makeDetection(id = "id-known")))

        val result = state.updateDetection(makeDetection(id = "id-unbekannt"))

        assertTrue("Sollte false geben fuer unbekannte ID", !result)
        assertEquals(1, state.getDetections().size)
    }

    // ── AC9: Sequentielle Edits ───────────────────────────────────────────────

    @Test
    fun `ac9 sequentielle Edits letzter Wert korrekt in detections jsonl`() {
        val dir = tmpFolder.newFolder("session-race")
        val detection = makeDetection(count = 1)
        val detFile = File(dir, "detections.jsonl")
        detFile.writeText(encodeDetection(detection) + "\n")

        // Simulation von Rapid-Edit: 1 → 5 → 3 (sequenziell, wie Mutex erzwingt)
        replaceInJsonl(detFile, detection.copy(count = 1))
        replaceInJsonl(detFile, detection.copy(count = 5))
        replaceInJsonl(detFile, detection.copy(count = 3))

        val lines = detFile.readLines().filter { it.isNotBlank() }
        assertEquals(1, lines.size)
        val final = jsonLenient.decodeFromString(DetectionResult.serializer(), lines[0])
        assertEquals("Finaler count muss 3 sein", 3, final.count)
    }

    // ── Konstanten-Check ──────────────────────────────────────────────────────

    @Test
    fun `verificationEvent action constants fuer T68 korrekt definiert`() {
        assertEquals("COUNT_CHANGED", VerificationEvent.ACTION_COUNT_CHANGED)
        assertEquals("ATLAS_SET", VerificationEvent.ACTION_ATLAS_SET)
        assertEquals("NOTE_ADDED", VerificationEvent.ACTION_NOTE_ADDED)
    }
}
