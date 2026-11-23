package ch.etasystems.pirol.ui.onboarding

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryWarningTest {
    @Test fun wenigRam_warnt() {
        assertTrue(MemoryWarning.isLowRam(3_800_000_000L))
        assertTrue(MemoryWarning.isLowRam(5_000_000_000L))
    }

    @Test fun genugRam_warntNicht() {
        assertFalse(MemoryWarning.isLowRam(5_800_000_000L))
        assertFalse(MemoryWarning.isLowRam(11_800_000_000L))
    }

    @Test fun unbekannt_warntNicht() {
        assertFalse(MemoryWarning.isLowRam(0L))
    }
}
