package ch.etasystems.pirol.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectionFixPolicyTest {
    private val now = 1_000_000L
    private fun fix(ageMs: Long, acc: Float?) = LocationData(47.0, 8.0, null, acc, now - ageMs)

    @Test fun noFix_needsFresh() = assertTrue(DetectionFixPolicy.needsFreshFix(null, now, 30f))
    @Test fun youngAccurate_noFresh() = assertFalse(DetectionFixPolicy.needsFreshFix(fix(5_000, 10f), now, 30f))
    @Test fun old_needsFresh() = assertTrue(DetectionFixPolicy.needsFreshFix(fix(16_000, 10f), now, 30f))
    @Test fun inaccurate_needsFresh() = assertTrue(DetectionFixPolicy.needsFreshFix(fix(1_000, 50f), now, 30f))
    @Test fun unknownAccuracy_needsFresh() = assertTrue(DetectionFixPolicy.needsFreshFix(fix(1_000, null), now, 30f))

    @Test fun freshAccurate_chosen() {
        val last = fix(60_000, 10f); val fresh = fix(0, 8f)
        val c = DetectionFixPolicy.choose(last, fresh, now, 30f)
        assertSame(fresh, c.fix); assertEquals(DetectionFixPolicy.Source.FRESH, c.source); assertFalse(c.stale)
    }

    @Test fun freshInaccurateButBetter_chosen() {
        val last = fix(60_000, 80f); val fresh = fix(0, 50f)
        assertSame(fresh, DetectionFixPolicy.choose(last, fresh, now, 30f).fix)
    }

    @Test fun freshWorseThanLast_keepsLastAndMarksStale() {
        val last = fix(60_000, 20f); val fresh = fix(0, 90f)
        val c = DetectionFixPolicy.choose(last, fresh, now, 15f)
        assertSame(last, c.fix); assertEquals(DetectionFixPolicy.Source.LAST, c.source); assertTrue(c.stale)
    }

    @Test fun freshTimeout_lastAttachedStale() {
        val last = fix(30_000, 10f)
        val c = DetectionFixPolicy.choose(last, null, now, 30f)
        assertSame(last, c.fix); assertTrue(c.stale)
    }

    @Test fun freshTimeout_lastYoung_notStale() {
        assertFalse(DetectionFixPolicy.choose(fix(1_000, 10f), null, now, 30f).stale)
    }

    @Test fun nothingAvailable() {
        val c = DetectionFixPolicy.choose(null, null, now, 30f)
        assertNull(c.fix); assertEquals(DetectionFixPolicy.Source.NONE, c.source)
    }

    @Test fun freshOnly_chosen() {
        val fresh = fix(0, 100f)
        assertSame(fresh, DetectionFixPolicy.choose(null, fresh, now, 30f).fix)
    }
}
