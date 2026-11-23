package ch.etasystems.pirol.ui

import ch.etasystems.pirol.ui.analysis.ManualEntryTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T203: Zeitfenster eines manuellen Eintrags aus der Abspielposition. */
class ManualEntryTimeTest {

    private val start = 1_000_000L
    private val now = 9_000_000L

    @Test
    fun middle_isPlusMinusTenSeconds() {
        val t = ManualEntryTime.compute(100f, 600f, start, now)
        assertTrue(t.hasPosition)
        assertEquals(90f, t.chunkStartSec, 0.001f)
        assertEquals(110f, t.chunkEndSec, 0.001f)
        assertEquals(start + 100_000L, t.timestampMs)
    }

    @Test
    fun atStart_clampedToZero() {
        val t = ManualEntryTime.compute(4f, 600f, start, now)
        assertEquals(0f, t.chunkStartSec, 0.001f)
        assertEquals(14f, t.chunkEndSec, 0.001f)
    }

    @Test
    fun atEnd_clampedToDuration() {
        val t = ManualEntryTime.compute(595f, 600f, start, now)
        assertEquals(585f, t.chunkStartSec, 0.001f)
        assertEquals(600f, t.chunkEndSec, 0.001f)
    }

    @Test
    fun positionBeyondDuration_isClamped() {
        val t = ManualEntryTime.compute(700f, 600f, start, now)
        assertEquals(590f, t.chunkStartSec, 0.001f)
        assertEquals(600f, t.chunkEndSec, 0.001f)
        assertEquals(start + 600_000L, t.timestampMs)
    }

    @Test
    fun withoutPosition_usesNow() {
        val t = ManualEntryTime.compute(null, 600f, start, now)
        assertFalse(t.hasPosition)
        assertEquals(0f, t.chunkStartSec, 0.001f)
        assertEquals(0f, t.chunkEndSec, 0.001f)
        assertEquals(now, t.timestampMs)
    }

    @Test
    fun withoutDurationOrSessionStart_usesNow() {
        assertFalse(ManualEntryTime.compute(10f, 0f, start, now).hasPosition)
        assertFalse(ManualEntryTime.compute(10f, 600f, null, now).hasPosition)
    }
}
