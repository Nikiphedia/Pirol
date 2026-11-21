package ch.etasystems.pirol.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class WhatsNewDecisionTest {
    @Test fun updateShowsDialog() {
        assertEquals(WhatsNewAction.SHOW, decideWhatsNew(9, 10, true))
    }

    @Test fun updateFromVersionWithoutStoredValueShows() {
        assertEquals(WhatsNewAction.SHOW, decideWhatsNew(null, 10, true))
    }

    @Test fun alreadySeenShowsNothing() {
        assertEquals(WhatsNewAction.NOTHING, decideWhatsNew(10, 10, true))
    }

    @Test fun freshInstallMarksSilently() {
        assertEquals(WhatsNewAction.MARK_SEEN, decideWhatsNew(null, 10, false))
    }

    @Test fun noDialogDuringOnboarding() {
        assertEquals(WhatsNewAction.NOTHING, decideWhatsNew(10, 10, false))
        assertEquals(WhatsNewAction.MARK_SEEN, decideWhatsNew(9, 10, false))
    }
}
