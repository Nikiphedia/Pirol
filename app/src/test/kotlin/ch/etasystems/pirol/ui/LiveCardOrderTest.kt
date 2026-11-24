package ch.etasystems.pirol.ui

import ch.etasystems.pirol.ui.live.LiveCardOrder
import ch.etasystems.pirol.ui.live.LiveCardOrder.Entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveCardOrderTest {
    private fun e(id: String, hit: Long = 0L, locked: Boolean = false) = Entry(id, locked, hit)

    @Test
    fun firstCardShownImmediately_newOnesCollectedUntilInterval() {
        val o = LiveCardOrder(5_000)
        assertEquals(listOf("a"), o.update(listOf(e("a", 1000)), 1000))
        assertEquals(listOf("a"), o.update(listOf(e("c", 3000), e("b", 2000), e("a", 1000)), 3000))
        assertEquals(2_000L, o.nextDueInMs(4000))
        assertEquals(listOf("c", "b", "a"), o.update(listOf(e("c", 3000), e("b", 2000), e("a", 1000)), 6000))
        assertNull(o.nextDueInMs(6000))
    }

    @Test
    fun hitInTopFive_staysAndBlinks() {
        val o = LiveCardOrder(5_000)
        o.update(listOf(e("a", 1000)), 1000)
        o.update(listOf(e("b", 6000), e("a", 1000)), 6000)
        val order = o.update(listOf(e("b", 6000), e("a", 9000)), 9000)
        assertEquals(listOf("b", "a"), order)
        assertEquals(9000L, o.blinkSignalMs("a"))
        assertNull(o.nextDueInMs(9000))
    }

    @Test
    fun hitBelowTopFive_movesUpAtNextSwitch() {
        val o = LiveCardOrder(5_000)
        val ids = listOf("a", "b", "c", "d", "e", "f", "g")
        val hits = HashMap<String, Long>()
        var t = 0L
        for ((i, id) in ids.withIndex()) {
            t += 5_000
            hits[id] = t
            o.update(ids.take(i + 1).reversed().map { e(it, hits[it]!!) }, t)
        }
        val base = o.update(ids.reversed().map { e(it, hits[it]!!) }, t)
        assertEquals(listOf("g", "f", "e", "d", "c", "b", "a"), base)
        val before = o.blinkSignalMs("a")
        hits["a"] = t + 1000
        val mid = o.update(ids.reversed().map { e(it, hits[it]!!) }, t + 1000)
        assertEquals(base, mid)
        assertEquals(before, o.blinkSignalMs("a"))
        val after = o.update(ids.reversed().map { e(it, hits[it]!!) }, t + 5000)
        assertEquals("a", after.first())
        assertEquals(t + 5000, o.blinkSignalMs("a"))
    }

    @Test
    fun removalIsImmediate() {
        val o = LiveCardOrder(5_000)
        o.update(listOf(e("a", 1000)), 1000)
        o.update(listOf(e("b", 6000), e("a", 1000)), 6000)
        assertEquals(listOf("b"), o.update(listOf(e("b", 6000)), 6100))
    }

    @Test
    fun removedPendingNeverAppears() {
        val o = LiveCardOrder(5_000)
        o.update(listOf(e("a", 1000)), 1000)
        o.update(listOf(e("b", 2000), e("a", 1000)), 2000)
        assertEquals(listOf("a"), o.update(listOf(e("a", 1000)), 3000))
        assertNull(o.nextDueInMs(3000))
    }

    @Test
    fun lockedFirstImmediately() {
        val o = LiveCardOrder(5_000)
        o.update(listOf(e("a", 1000)), 1000)
        o.update(listOf(e("b", 6000), e("a", 1000)), 6000)
        assertEquals(listOf("a", "b"), o.update(listOf(e("b", 6000), e("a", 1000, locked = true)), 6001))
    }

    @Test
    fun resetStartsOver() {
        val o = LiveCardOrder(5_000)
        o.update(listOf(e("a", 1000)), 1000)
        o.reset()
        assertEquals(listOf("x"), o.update(listOf(e("x", 1500)), 1500))
        assertEquals(0L, o.blinkSignalMs("a"))
    }
}
