package ch.etasystems.pirol.data.repository

import ch.etasystems.pirol.ml.VerificationEvent
import ch.etasystems.pirol.ml.VerificationStatus
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/** T117: Undo darf nur das letzte Status-Ereignis der Detektion entfernen, nie eine Cluster-Zusammenfuehrung. */
class UndoStatusEventTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private fun line(id: String, status: VerificationStatus, action: String?) = json.encodeToString(
        VerificationEvent.serializer(),
        VerificationEvent(detectionId = id, status = status, verifiedAtMs = 1L, actionType = action)
    )
    private fun idx(lines: List<String>, id: String) =
        SessionManager.indexOfLastStatusEvent(lines, id) { json.decodeFromString(VerificationEvent.serializer(), it) }

    @Test
    fun `Cluster-Merge nach Ablehnung wird uebersprungen`() {
        val lines = listOf(
            line("a", VerificationStatus.REJECTED, VerificationEvent.ACTION_REJECTED),
            line("a", VerificationStatus.REJECTED, VerificationEvent.ACTION_CLUSTER_MERGED)
        )
        assertEquals(0, idx(lines, "a"))
    }

    @Test
    fun `Legacy-Eintrag ohne actionType zaehlt als Status`() {
        val lines = listOf(line("a", VerificationStatus.CONFIRMED, null), line("b", VerificationStatus.CONFIRMED, null))
        assertEquals(0, idx(lines, "a"))
    }

    @Test
    fun `Bemerkung und Anzahl werden nicht entfernt`() {
        val lines = listOf(
            line("a", VerificationStatus.UNVERIFIED, VerificationEvent.ACTION_NOTE_ADDED),
            line("a", VerificationStatus.UNVERIFIED, VerificationEvent.ACTION_COUNT_CHANGED)
        )
        assertEquals(-1, idx(lines, "a"))
    }

    @Test
    fun `defekte Zeilen werden uebersprungen`() {
        val lines = listOf(line("a", VerificationStatus.UNCERTAIN, VerificationEvent.ACTION_UNCERTAIN), "{kaputt")
        assertEquals(0, idx(lines, "a"))
    }
}
