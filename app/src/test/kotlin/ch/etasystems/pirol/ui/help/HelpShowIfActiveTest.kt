package ch.etasystems.pirol.ui.help

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T280: Abfang fuer Elemente ausserhalb von Compose (osmdroid-Marker) */
class HelpShowIfActiveTest {

    @Test
    fun inactiveDoesNothing() {
        val s = HelpModeState()
        assertFalse(s.showIfActive(42, "8"))
        assertNull(s.bubble)
    }

    @Test
    fun activeShowsBubbleAndConsumes() {
        val s = HelpModeState()
        s.active = true
        assertTrue(s.showIfActive(42, "8"))
        assertEquals(HelpTopic(42, "8"), s.bubble)
    }
}
