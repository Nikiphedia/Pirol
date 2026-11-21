package ch.etasystems.pirol.ui.map

import ch.etasystems.pirol.ml.VerificationEvent
import ch.etasystems.pirol.ml.VerificationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MapPositionMoveTest {

    private fun ev(type: String?, value: String?) = VerificationEvent(
        detectionId = "d1", status = VerificationStatus.UNVERIFIED, verifiedAtMs = 1L,
        actionType = type, newValue = value
    )

    private fun marker(id: String, lat: Double, lon: Double) = MapViewModel.MapMarker(
        detectionId = id, sessionId = "s", latitude = lat, longitude = lon, species = "X",
        confidence = 0.9f, timestampMs = 0L, verificationStatus = VerificationStatus.UNVERIFIED
    )

    @Test fun parsesValidPosition() {
        assertEquals(47.5 to 8.25, MapPositionMove.parse(ev(VerificationEvent.ACTION_POSITION_MOVED, "47.5,8.25")))
    }

    @Test fun rejectsWrongTypeOrGarbage() {
        assertNull(MapPositionMove.parse(ev(VerificationEvent.ACTION_COUNT_CHANGED, "47.5,8.25")))
        assertNull(MapPositionMove.parse(ev(VerificationEvent.ACTION_POSITION_MOVED, "abc")))
        assertNull(MapPositionMove.parse(ev(VerificationEvent.ACTION_POSITION_MOVED, "1,2,3")))
        assertNull(MapPositionMove.parse(ev(VerificationEvent.ACTION_POSITION_MOVED, "95.0,8.0")))
        assertNull(MapPositionMove.parse(ev(VerificationEvent.ACTION_POSITION_MOVED, null)))
    }

    @Test fun applyChangesOnlyMatchingMarker() {
        val out = MapPositionMove.applyTo(listOf(marker("a", 1.0, 2.0), marker("b", 3.0, 4.0)), "b", 9.0, 8.0)
        assertEquals(1.0, out[0].latitude, 0.0)
        assertEquals(9.0, out[1].latitude, 0.0)
        assertEquals(8.0, out[1].longitude, 0.0)
    }
}
