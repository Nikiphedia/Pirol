package ch.etasystems.pirol.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit-Tests fuer die Full-Screen-vs-Heads-up-Entscheidung (T135). */
class AlarmPolicyTest {

    @Test fun oldAndroid_fullScreenWithoutPermissionCheck() {
        assertEquals(AlarmPresentation.FULL_SCREEN, AlarmPolicy.decide(true, true, 33, false))
    }

    @Test fun android14_allowed_fullScreen() {
        assertEquals(AlarmPresentation.FULL_SCREEN, AlarmPolicy.decide(true, true, 36, true))
    }

    @Test fun android14_notAllowed_headsUp() {
        assertEquals(AlarmPresentation.HEADS_UP, AlarmPolicy.decide(true, true, 34, false))
    }

    @Test fun notificationDisabledOrBlocked_none() {
        assertEquals(AlarmPresentation.NONE, AlarmPolicy.decide(false, true, 36, true))
        assertEquals(AlarmPresentation.NONE, AlarmPolicy.decide(true, false, 36, true))
    }

    @Test fun hint_onlyOnAndroid14WithoutPermission() {
        assertTrue(AlarmPolicy.needsFullScreenHint(34, false))
        assertFalse(AlarmPolicy.needsFullScreenHint(34, true))
        assertFalse(AlarmPolicy.needsFullScreenHint(33, false))
    }
}
