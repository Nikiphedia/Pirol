package ch.etasystems.pirol.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactCardMenuTest {
    @Test
    fun vollerInventarEnthaeltAlleAktionen() {
        val e = CompactCardMenu.entries(true, true, true, true, true, true, true, true, true)
        assertEquals(CompactMenuEntry.values().toSet(), e.toSet())
        assertEquals(CompactMenuEntry.values().size, e.size)
    }

    @Test
    fun fehlendeCallbacksFehlenImMenue() {
        val e = CompactCardMenu.entries(false, false, false, false, false, false, false, false, false)
        assertTrue(e.isEmpty())
        val nurPlay = CompactCardMenu.entries(false, false, false, true, false, false, false, false, false)
        assertEquals(listOf(CompactMenuEntry.PLAY_TOGGLE), nurPlay)
    }
}
