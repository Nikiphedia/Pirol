package ch.etasystems.pirol.ui.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class LruCacheMapTest {
    @Test
    fun sameKeyCreatesOnce() {
        val c = LruCacheMap<MarkerIconKey, Any>(4)
        var n = 0
        val k = MarkerIconKey(1, "A", false, 64, null, 1, null)
        val a = c.getOrPut(k) { n++; Any() }
        val b = c.getOrPut(k.copy()) { n++; Any() }
        assertSame(a, b)
        assertEquals(1, n)
    }

    @Test
    fun evictsLeastRecentlyUsed() {
        val c = LruCacheMap<Int, String>(2)
        c.getOrPut(1) { "a" }; c.getOrPut(2) { "b" }; c.getOrPut(1) { "x" }; c.getOrPut(3) { "c" }
        assertEquals(2, c.size)
        var created = false
        c.getOrPut(2) { created = true; "b2" }
        assertEquals(true, created)
    }
}
