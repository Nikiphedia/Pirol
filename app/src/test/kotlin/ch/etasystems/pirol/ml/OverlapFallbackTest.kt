package ch.etasystems.pirol.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * T110: Entscheidungslogik des Overlap-Auto-Fallbacks + Cluster-Merge ueberlappender Fenster.
 */
class OverlapFallbackTest {

    @Test
    fun `Stufen und Hops`() {
        assertEquals(3000, OverlapFallback.hopMsFor(0))
        assertEquals(1500, OverlapFallback.hopMsFor(50))
        assertEquals(1000, OverlapFallback.hopMsFor(67))
        assertEquals(50, OverlapFallback.stepDown(67))
        assertEquals(0, OverlapFallback.stepDown(50))
        assertEquals(0, OverlapFallback.normalize(30))
        assertEquals(67, OverlapFallback.normalize(80))
    }

    @Test
    fun `schnelle Inference — keine Reduktion`() {
        val f = OverlapFallback()
        repeat(50) { assertNull(f.evaluate(67, 1000, 300, 0)) }
    }

    @Test
    fun `Median ueber 80 Prozent des Hops — Reduktion 67 auf 50 erst nach minSamples`() {
        val f = OverlapFallback()
        repeat(4) { assertNull(f.evaluate(67, 1000, 900, 0)) }
        assertEquals(50, f.evaluate(67, 1000, 900, 0))
        // Bei 1500 ms Hop reichen 900 ms (60 %)
        repeat(20) { assertNull(f.evaluate(50, 1500, 900, 0)) }
    }

    @Test
    fun `einzelne Ausreisser loesen keine Reduktion aus (Median)`() {
        val f = OverlapFallback()
        repeat(30) { i -> assertNull(f.evaluate(67, 1000, if (i % 3 == 0) 2500 else 200, 0)) }
    }

    @Test
    fun `Stau (verworfene Fenster) — Reduktion auch bei kurzer Messreihe`() {
        val f = OverlapFallback()
        assertNull(f.evaluate(50, 1500, 100, 1))
        assertEquals(0, f.evaluate(50, 1500, 100, 2))
    }

    @Test
    fun `stufenweise 67 - 50 - 0 und bei 0 keine weitere Reduktion`() {
        val f = OverlapFallback()
        var level = 67
        val hop = { l: Int -> OverlapFallback.hopMsFor(l).toLong() }
        var guard = 0
        while (level > 0 && guard++ < 100) {
            f.evaluate(level, hop(level), 2000, 0)?.let { level = it }
        }
        assertEquals(0, level)
        repeat(20) { assertNull(f.evaluate(0, 3000, 5000, 99)) }
    }

    @Test
    fun `Energiesparmodus — Budget ist das Intervall, nicht der Hop`() {
        val f = OverlapFallback()
        repeat(20) { assertNull(f.evaluate(67, 15000, 900, 0)) }
    }

    @Test
    fun `zwei aufeinanderfolgende ueberlappende Fenster derselben Art ergeben eine Karte`() {
        val state = DetectionListState()
        val t0 = System.currentTimeMillis() - 3000
        fun det(id: String, ts: Long, start: Float) = DetectionResult(
            id = id, scientificName = "Turdus merula", commonName = "Amsel", confidence = 0.8f,
            timestampMs = ts, chunkStartSec = start, chunkEndSec = start + 3f, sampleRate = 48000,
            latitude = 47.3769, longitude = 8.5417
        )
        val r1 = state.addOrCluster(det("a", t0, 10f), true, 10, 5)
        val r2 = state.addOrCluster(det("b", t0 + 1000, 11f), true, 10, 5)
        val r3 = state.addOrCluster(det("c", t0 + 2000, 12f), true, 10, 5)
        assertEquals(true, r1 is ClusterResult.New)
        assertEquals(true, r2 is ClusterResult.Merged)
        assertEquals(true, r3 is ClusterResult.Merged)
        assertEquals(1, state.getDetections().size)
        assertEquals(3, state.getDetections().first().detectionCount)
        assertEquals(10f, state.getDetections().first().chunkStartSec)
    }
}
