package ch.etasystems.pirol.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** T180: Auswahl-State, Konfliktnamen, Ergebnis/Fortschritt (SAF-Kopie nur auf dem Geraet testbar). */
class SessionBatchLogicTest {

    @Test fun toggle_fuegt_hinzu_und_entfernt() {
        val a = SessionBatchLogic.toggle(emptySet(), "s1")
        assertEquals(setOf("s1"), a)
        assertTrue(SessionBatchLogic.toggle(a, "s1").isEmpty())
    }

    @Test fun selectAll_und_prune() {
        assertEquals(setOf("a", "b"), SessionBatchLogic.selectAll(listOf("a", "b", "a")))
        assertEquals(setOf("a"), SessionBatchLogic.prune(setOf("a", "x"), listOf("a", "b")))
    }

    @Test fun uniqueName_ohne_Konflikt() {
        assertEquals("s1", SessionBatchLogic.uniqueName("s1", listOf("s2")))
    }

    @Test fun uniqueName_Suffix_hochzaehlen_ohne_Gross_Klein() {
        assertEquals("s1-2", SessionBatchLogic.uniqueName("s1", listOf("S1")))
        assertEquals("s1-3", SessionBatchLogic.uniqueName("s1", listOf("s1", "s1-2")))
    }

    @Test fun batchResult_total() {
        val r = BatchResult(succeeded = 3, failed = 2, failedNames = listOf("a", "b"))
        assertEquals(5, r.total)
    }

    @Test fun moveProgress_fraction() {
        assertEquals(0.5f, MoveProgress(0, 1, "s", 50, 100, 1, 2).fraction, 0.001f)
        assertEquals(0.5f, MoveProgress(0, 1, "s", 0, 0, 1, 2).fraction, 0.001f)
        assertEquals(0f, MoveProgress(0, 1, "s", 0, 0, 0, 0).fraction, 0.001f)
        assertEquals(1f, MoveProgress(0, 1, "s", 200, 100, 0, 0).fraction, 0.001f)
    }
}
