package ch.etasystems.pirol.data.repository

import ch.etasystems.pirol.data.export.RavenExporter
import ch.etasystems.pirol.ml.DetectionListState
import ch.etasystems.pirol.ml.DetectionResult
import ch.etasystems.pirol.ml.VerificationEvent
import ch.etasystems.pirol.ml.VerificationStatus
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** T249: M1 (Nutzer-Aktion gegen Pipeline-Update), M2 (eine Raven-Feldliste), M3 (letzter Flush), M6 (Undo Alternative). */
class PersistenceEdgeTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun det(id: String) = DetectionResult(
        id = id, scientificName = "Turdus merula", commonName = "Amsel", confidence = 0.8f,
        timestampMs = 1000L, chunkStartSec = 0f, chunkEndSec = 3f, sampleRate = 48000
    )

    private fun line(d: DetectionResult) = json.encodeToString(DetectionResult.serializer(), d) + "\n"

    private fun flushed(queue: DetectionUpdateQueue, fileText: String): DetectionResult {
        var out = fileText
        queue.flush({ out }) { out = it }
        return json.decodeFromString(DetectionResult.serializer(), out.trim())
    }

    // ── M1 ───────────────────────────────────────────────────────────

    @Test fun m1_nutzerAktionNachMerge_schreibtAktuellenListenstand() {
        val list = DetectionListState().apply { addDetections(listOf(det("d"))) }
        val queue = DetectionUpdateQueue()
        val file = line(det("d"))

        // Nutzer setzt Anzahl (Kopie `updated` wird gehalten, Schreiben wartet auf verificationMutex)
        val updated = list.modifyDetection("d") { it.copy(count = 4) }!!
        // Inzwischen: Cluster-Merge + Zweitmodell, Pipeline merkt unter der Listen-Sperre vor
        list.modifyDetection("d") { it.copy(detectionCount = 2, lastSeenAtMs = 4000L, secondaryModelId = "m2") }
        list.withCurrent("d") { queue.put(it) }

        // Alter Weg (replaceDetection(updated)) haette den neueren Stand ueberschrieben
        val stale = DetectionUpdateQueue().apply { put(updated) }
        assertEquals(1, flushed(stale, file).detectionCount)

        // T249: Nutzer-Pfad holt den Stand ebenfalls unter der Listen-Sperre
        list.withCurrent("d") { queue.put(it) }
        val written = flushed(queue, file)
        assertEquals(4, written.count)
        assertEquals(2, written.detectionCount)
        assertEquals(4000L, written.lastSeenAtMs)
        assertEquals("m2", written.secondaryModelId)
    }

    @Test fun m1_rollback_schreibtZurueckgesetztenListenstand_nichtAltesBefore() {
        val list = DetectionListState().apply { addDetections(listOf(det("d"))) }
        val queue = DetectionUpdateQueue()
        val before = list.getDetections().first()
        list.modifyDetection("d") { it.copy(scientificName = "Parus major") }       // Alternative
        list.modifyDetection("d") { it.copy(detectionCount = 3) }                   // Merge danach
        list.modifyDetection("d") { it.copy(scientificName = before.scientificName) } // Rollback nur Art
        list.withCurrent("d") { queue.put(it) }
        val written = flushed(queue, line(det("d")))
        assertEquals("Turdus merula", written.scientificName)
        assertEquals(3, written.detectionCount)
    }

    // ── M2 ───────────────────────────────────────────────────────────

    @Test fun m2_eineFeldliste_enthaeltAlleFelderInklusiveZweitmodell() {
        val all = RavenExporter.enabledFields { true }
        assertEquals(RavenExporter.ALL_FIELDS.size, all.size)
        for (f in listOf(
            RavenExporter.FIELD_SECONDARY, RavenExporter.FIELD_SIMULTAN,
            RavenExporter.FIELD_MOEGLICH, RavenExporter.FIELD_GLEICH, RavenExporter.FIELD_BEGIN_FILE
        )) assertTrue(f, f in all)
        assertEquals(setOf(RavenExporter.FIELD_SECONDARY),
            RavenExporter.enabledFields { it == RavenExporter.FIELD_SECONDARY })
    }

    // ── M3 ───────────────────────────────────────────────────────────

    @Test fun m3_letzterFlush_wirdWiederholt() = runBlocking {
        var calls = 0
        val err = SessionManager.retryFlush(3, 1L) { calls++; if (calls < 3) IOException("kurz") else null }
        assertNull(err)
        assertEquals(3, calls)
    }

    @Test fun m3_dauerfehler_wirdGemeldet_nichtStill() = runBlocking {
        var calls = 0
        val err = SessionManager.retryFlush(3, 1L) { calls++; IOException("voll") }
        assertTrue(err is IOException)
        assertEquals(3, calls)
    }

    @Test fun m3_fehlerBeimRewrite_updatesBleibenVorgemerkt() {
        val queue = DetectionUpdateQueue().apply { put(det("d").copy(count = 2)) }
        try { queue.flush({ line(det("d")) }) { throw IOException("voll") } } catch (_: IOException) { }
        assertEquals(1, queue.size())
    }

    // ── M6 ───────────────────────────────────────────────────────────

    private fun ev(id: String, status: VerificationStatus, action: String?) = json.encodeToString(
        VerificationEvent.serializer(),
        VerificationEvent(detectionId = id, status = status, verifiedAtMs = 1L, actionType = action)
    )

    @Test fun m6_undoAlternative_entferntGenauDasAlternativEreignis() {
        // Alternative gewaehlt, danach "Unsicher" auf derselben Karte, dann Undo der Alternative
        val text = listOf(
            ev("a", VerificationStatus.CONFIRMED, VerificationEvent.ACTION_ALTERNATIVE_CONFIRMED),
            ev("a", VerificationStatus.UNCERTAIN, VerificationEvent.ACTION_UNCERTAIN)
        ).joinToString("\n") + "\n"
        val out = SessionManager.removeLastStatusEventText(text, "a", VerificationEvent.ACTION_ALTERNATIVE_CONFIRMED)!!
        val left = out.lines().filter { it.isNotBlank() }
            .map { json.decodeFromString(VerificationEvent.serializer(), it) }
        assertEquals(1, left.size)
        assertEquals(VerificationEvent.ACTION_UNCERTAIN, left[0].actionType)
        // Ohne Typ (bisheriges Verhalten) waere das UNCERTAIN-Ereignis entfernt worden
        val old = SessionManager.removeLastStatusEventText(text, "a")!!
        assertTrue(old.contains(VerificationEvent.ACTION_ALTERNATIVE_CONFIRMED))
    }

    @Test fun m6_ohneAlternativEreignis_keineAenderung() {
        val text = ev("a", VerificationStatus.UNCERTAIN, VerificationEvent.ACTION_UNCERTAIN) + "\n"
        assertNull(SessionManager.removeLastStatusEventText(text, "a", VerificationEvent.ACTION_ALTERNATIVE_CONFIRMED))
    }
}
