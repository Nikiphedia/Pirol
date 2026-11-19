package ch.etasystems.pirol.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PendingAlarmUndoTest {
    private var now = 1_000L
    private val undo = PendingAlarmUndo { now }
    private val entry = PendingAlarmUndo.Entry("d1", "Amsel", VerificationStatus.UNVERIFIED, null, true)

    @Test fun `take within window returns entry once`() {
        undo.record(entry)
        now += 29_000
        assertEquals(entry, undo.take())
        assertNull(undo.take())
    }

    @Test fun `take after window returns null`() {
        undo.record(entry)
        now += 31_000
        assertNull(undo.take())
    }

    @Test fun `newer record replaces older and notifies`() {
        var calls = 0
        undo.onRecorded = { calls++ }
        undo.record(entry)
        val e2 = entry.copy(detectionId = "d2")
        undo.record(e2)
        assertEquals(2, calls)
        assertEquals(e2, undo.take())
    }

    @Test fun `clear removes entry`() {
        undo.record(entry)
        undo.clear()
        assertNull(undo.take())
    }
}
