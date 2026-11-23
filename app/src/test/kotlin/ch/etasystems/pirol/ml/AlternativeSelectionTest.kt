package ch.etasystems.pirol.ml

import ch.etasystems.pirol.data.repository.SessionFilesLogic
import ch.etasystems.pirol.data.repository.SessionManager
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T237: Alternative waehlen ersetzt die Art der bestehenden Detektion. */
class AlternativeSelectionTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val cand = DetectionCandidate("Parus major", "Kohlmeise", 0.4f)

    private fun det(id: String, sci: String = "Turdus merula", common: String = "Amsel") = DetectionResult(
        id = id, scientificName = sci, commonName = common, confidence = 0.9f,
        timestampMs = 1_000L + id.hashCode().mod(100), chunkStartSec = 12f, chunkEndSec = 15f, sampleRate = 48000,
        latitude = 47.1, longitude = 8.2, count = 3, note = "n", bestStartSec = 13f, bestEndSec = 16f,
        candidates = listOf(cand, DetectionCandidate("Sylvia atricapilla", "Moenchsgrasmuecke", 0.2f)),
        secondaryModelId = "m", secondaryStatus = "CHECKED", secondaryConfidence = 0.8f
    )

    @Test fun apply_ersetzt_Art_und_behaelt_Identitaet_Zeit_Fenster() {
        val o = det("a")
        val u = AlternativeSelection.apply(o, cand, nowMs = 999_999L)
        assertEquals("a", u.id)
        assertEquals("Parus major", u.scientificName)
        assertEquals("Kohlmeise", u.commonName)
        assertEquals(0.4f, u.confidence)
        assertEquals(o.timestampMs, u.timestampMs)           // Aufnahmezeit, nicht Wanduhr der Wahl
        assertEquals(12f, u.chunkStartSec); assertEquals(15f, u.chunkEndSec)
        assertEquals(13f, u.bestStartSec); assertEquals(16f, u.bestEndSec)
        assertEquals(47.1, u.latitude); assertEquals(3, u.count); assertEquals("n", u.note)
        assertEquals(VerificationStatus.CONFIRMED, u.verificationStatus)
        assertNull(u.correctedSpecies)
        assertNull(u.secondaryStatus)                         // galt der alten Art
        // alte Art ist jetzt Kandidat, gewaehlte nicht mehr doppelt
        assertEquals(listOf("Turdus merula", "Sylvia atricapilla"),
            u.candidates.map { it.scientificName }.sortedByDescending { n -> if (n == "Turdus merula") 1 else 0 })
        assertFalse(u.candidates.any { it.scientificName == "Parus major" })
    }

    @Test fun liste_andere_Detektionen_unberuehrt_auch_gleiche_Art() {
        val state = DetectionListState()
        val a = det("a"); val b = det("b", "Parus major", "Kohlmeise"); val c = det("c", "Erithacus rubecula", "Rotkehlchen")
        state.addDetections(listOf(a, b, c))
        val before = state.selectAlternative("a", cand, 5L)
        assertNotNull(before)
        val list = state.getDetections()
        assertEquals(3, list.size)                            // nichts entfernt, nichts angehaengt
        assertEquals(b.copy(lastDetectedMs = list.first { it.id == "b" }.lastDetectedMs), list.first { it.id == "b" })
        assertEquals(c.copy(lastDetectedMs = list.first { it.id == "c" }.lastDetectedMs), list.first { it.id == "c" })
        assertEquals("Parus major", list.first { it.id == "a" }.scientificName)
        assertNull(state.selectAlternative("fehlt", cand))
    }

    @Test fun undo_stellt_Felder_wieder_her_und_laesst_spaetere_Aenderungen() {
        val state = DetectionListState()
        val a = det("a")
        state.addDetections(listOf(a))
        val before = state.selectAlternative("a", cand, 5L)!!
        state.modifyDetection("a") { it.copy(count = 9) }     // z.B. Nutzer aendert Stueckzahl danach
        state.modifyDetection("a") { AlternativeSelection.revert(it, before) }
        val r = state.getDetections().single()
        assertEquals(a.copy(count = 9, lastDetectedMs = r.lastDetectedMs), r)
    }

    @Test fun datei_und_verifikation_konsistent_und_undo_entfernt_Event() {
        val a = det("a"); val b = det("b", "Parus major", "Kohlmeise")
        val text = json.encodeToString(a) + "\n" + json.encodeToString(b) + "\n"
        val u = AlternativeSelection.apply(a, cand, 5L)
        val out = SessionFilesLogic.replaceDetectionInText(text, u)
        val lines = out.lines().filter { it.isNotBlank() }.map { json.decodeFromString<DetectionResult>(it) }
        assertEquals(listOf("a", "b"), lines.map { it.id })
        assertEquals("Parus major", lines[0].scientificName)
        assertEquals(b, lines[1])

        val conf = VerificationEvent("a", VerificationStatus.CONFIRMED, verifiedAtMs = 5L,
            actionType = VerificationEvent.ACTION_ALTERNATIVE_CONFIRMED, newValue = "Turdus merula")
        val other = VerificationEvent("b", VerificationStatus.CONFIRMED, verifiedAtMs = 6L,
            actionType = VerificationEvent.ACTION_CONFIRMED)
        val vtext = json.encodeToString(other) + "\n" + json.encodeToString(conf) + "\n"
        val after = SessionManager.removeLastStatusEventText(vtext, "a")!!
        assertEquals(json.encodeToString(other) + "\n", after)
        assertNull(SessionManager.removeLastStatusEventText(after, "a"))
        assertTrue(SessionManager.removeLastStatusEventText(json.encodeToString(conf) + "\n", "a") == "")
    }

    /** T251 (Zusatz T249): Undo nach zwischenzeitlicher Verifikation behaelt den Status (wie die Datei). */
    @Test fun undo_nach_zwischenzeitlicher_Verifikation_behaelt_Status() {
        val a = det("a")
        val applied = AlternativeSelection.apply(a, cand, 5L)
        val uncertain = applied.copy(verificationStatus = VerificationStatus.UNCERTAIN, verifiedAtMs = 9L)
        val r = AlternativeSelection.undo(uncertain, before = a, applied = applied)
        assertEquals(a.scientificName, r.scientificName)
        assertEquals(a.candidates, r.candidates)
        assertEquals(a.secondaryStatus, r.secondaryStatus)
        assertEquals(VerificationStatus.UNCERTAIN, r.verificationStatus)
        assertEquals(9L, r.verifiedAtMs)
    }

    @Test fun undo_ohne_zwischenzeitliche_Verifikation_stellt_Status_her() {
        val a = det("a")
        val applied = AlternativeSelection.apply(a, cand, 5L)
        val r = AlternativeSelection.undo(applied.copy(count = 7), before = a, applied = applied)
        assertEquals(a.copy(count = 7), r)
    }
}
