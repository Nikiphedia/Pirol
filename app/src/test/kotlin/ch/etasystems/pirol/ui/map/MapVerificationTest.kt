package ch.etasystems.pirol.ui.map

import ch.etasystems.pirol.ml.VerificationEvent
import ch.etasystems.pirol.ml.VerificationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapVerificationTest {
    private val marker = MapViewModel.MapMarker(
        detectionId = "d1", sessionId = "s1", latitude = 47.0, longitude = 8.0,
        species = "Amsel", confidence = 0.9f, timestampMs = 1L,
        verificationStatus = VerificationStatus.UNVERIFIED
    )

    @Test fun eventForCarriesStatus() {
        val e = MapVerification.eventFor("d1", VerificationStatus.REJECTED, 5L)
        assertEquals("d1", e.detectionId)
        assertEquals(VerificationStatus.REJECTED, e.status)
        assertEquals(5L, e.verifiedAtMs)
        assertNull(e.actionType)
    }

    @Test fun undoRestoresPreviousStatus() {
        val rec = MapVerification.UndoRecord(marker, VerificationStatus.UNCERTAIN, null)
        val e = MapVerification.undoEvent(rec, 9L)
        assertEquals(VerificationStatus.UNCERTAIN, e.status)
        assertEquals(VerificationEvent.ACTION_UNDO, e.actionType)
    }

    @Test fun rejectedHiddenOthersVisible() {
        assertFalse(MapVerification.visibleAfter(VerificationStatus.REJECTED))
        assertTrue(MapVerification.visibleAfter(VerificationStatus.CONFIRMED))
        assertTrue(MapVerification.visibleAfter(VerificationStatus.UNCERTAIN))
    }

    @Test fun messageAndWithStatus() {
        assertTrue(MapVerification.message(VerificationStatus.REJECTED, "Amsel").contains("abgelehnt"))
        assertEquals(VerificationStatus.CONFIRMED,
            MapVerification.withStatus(marker, VerificationStatus.CONFIRMED).verificationStatus)
    }
}
