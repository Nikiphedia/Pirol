package ch.etasystems.pirol.location

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectionFixGateTest {
    private var now = 1_000_000L
    private val gate = DetectionFixGate { now }
    private fun fix(ageMs: Long, acc: Float? = 10f) = LocationData(47.0, 8.0, null, acc, now - ageMs)

    @Test fun youngFix_isReused_noRequest() = runBlocking {
        var calls = 0
        val last = fix(10_000)
        val r = gate.obtain(last, 30f) { calls++; fix(0) }
        assertSame(last, r.choice.fix); assertEquals(0, calls); assertEquals(1, gate.reusedCount)
    }

    @Test fun oldFix_requestsFresh() = runBlocking {
        val fresh = fix(0, 8f)
        val r = gate.obtain(fix(40_000), 30f) { fresh }
        assertSame(fresh, r.choice.fix); assertTrue(r.fetched); assertEquals(1, gate.freshCount)
    }

    @Test fun manyDetectionsWithin30s_exactlyOneFix() = runBlocking {
        var calls = 0
        repeat(5) {
            gate.obtain(fix(60_000), 30f) { calls++; fix(0) }
            now += 5_000
        }
        assertEquals(1, calls)
    }

    @Test fun after30s_newFix() = runBlocking {
        var calls = 0
        gate.obtain(null, 30f) { calls++; fix(0) }
        now += 31_000
        gate.obtain(null, 30f) { calls++; fix(0) }
        assertEquals(2, calls)
    }

    @Test fun failure_returnsLastWithAge_andBlocksRetryFor30s() = runBlocking {
        var calls = 0
        val last = fix(120_000)
        val r = gate.obtain(last, 30f) { calls++; null }
        assertSame(last, r.choice.fix); assertTrue(r.choice.stale); assertEquals(0, gate.freshCount)
        now += 10_000
        gate.obtain(last, 30f) { calls++; null }
        assertEquals(1, calls)
    }

    @Test fun failureWithoutLast_noFix() = runBlocking {
        assertNull(gate.obtain(null, 30f) { null }.choice.fix)
    }

    @Test fun concurrentDetections_oneFix() = runBlocking {
        var calls = 0
        val results = (1..4).map {
            async { gate.obtain(null, 30f) { calls++; delay(50); fix(0) } }
        }.awaitAll()
        assertEquals(1, calls)
        assertTrue(results.all { it.choice.fix != null })
    }

    @Test fun seed_countsAsFixAndGap() = runBlocking {
        gate.seed(fix(0))
        var calls = 0
        now += 20_000
        val r = gate.obtain(null, 30f) { calls++; fix(0) }
        assertEquals(0, calls); assertEquals(gate.latestFix(), r.choice.fix)
    }
}
