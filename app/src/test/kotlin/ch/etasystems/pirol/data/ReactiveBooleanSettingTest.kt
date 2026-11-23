package ch.etasystems.pirol.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReactiveBooleanSettingTest {
    @Test
    fun toggleSetsValueAndPersists() {
        val saved = mutableListOf<Boolean>()
        val s = ReactiveBooleanSetting(false) { saved += it }
        assertFalse(s.flow.value)
        s.toggle()
        assertTrue(s.flow.value)
        assertTrue(s.value)
        s.toggle()
        assertFalse(s.value)
        assertEquals(listOf(true, false), saved)
    }

    @Test
    fun setPublishesToFlow() {
        val s = ReactiveBooleanSetting(false) {}
        s.set(true)
        assertTrue(s.flow.value)
    }
}
