package ch.etasystems.pirol.ui.map

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SideSwipeTrackerTest {
    @Test fun firesOnceWhenThresholdReached() {
        val t = SideSwipeTracker(100f)
        assertFalse(t.onDrag(40f))
        assertFalse(t.onDrag(40f))
        assertTrue(t.onDrag(30f))
        assertFalse(t.onDrag(50f))
    }

    @Test fun backwardMovementReducesTotal() {
        val t = SideSwipeTracker(100f)
        assertFalse(t.onDrag(90f))
        assertFalse(t.onDrag(-50f))
        assertFalse(t.onDrag(40f))
        assertTrue(t.onDrag(20f))
    }

    @Test fun resetStartsNewGesture() {
        val t = SideSwipeTracker(50f)
        assertTrue(t.onDrag(60f))
        t.reset()
        assertFalse(t.onDrag(20f))
        assertTrue(t.onDrag(40f))
    }

    @Test fun wrongDirectionNeverFires() {
        val t = SideSwipeTracker(50f)
        repeat(10) { assertFalse(t.onDrag(-30f)) }
    }
}
