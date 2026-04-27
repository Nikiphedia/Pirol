package ch.etasystems.pirol.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoDeleteTest {

    @Test fun ac1_shortEmptySession_deleted() =
        assertTrue(SessionManager.shouldAutoDelete(5.0, 0, 10, true))

    @Test fun ac2_shortSessionWithDetection_kept() =
        assertFalse(SessionManager.shouldAutoDelete(8.0, 1, 10, true))

    @Test fun ac3_longEmptySession_kept() =
        assertFalse(SessionManager.shouldAutoDelete(12.0, 0, 10, true))

    @Test fun ac4_toggleOff_neverDeleted() =
        assertFalse(SessionManager.shouldAutoDelete(5.0, 0, 10, false))

    @Test fun ac5_minSecZero_neverDeleted() =
        assertFalse(SessionManager.shouldAutoDelete(5.0, 0, 0, true))

    @Test fun ac6_sliderAt30_25sEmptyDeleted() =
        assertTrue(SessionManager.shouldAutoDelete(25.0, 0, 30, true))
}
