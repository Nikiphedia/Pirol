package ch.etasystems.pirol.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpectrogramRingTest {

    private fun runs(r: List<IntArray>) = r.map { it.toList() }

    @Test
    fun ringWriteOhneUmbruch() {
        val ring = ColumnRing(10)
        assertEquals(listOf(listOf(0, 3)), runs(ring.write(3)))
        assertEquals(3, ring.head)
    }

    @Test
    fun ringWriteMitUmbruch() {
        val ring = ColumnRing(10)
        ring.write(8)
        assertEquals(listOf(listOf(8, 2), listOf(0, 3)), runs(ring.write(5)))
        assertEquals(3, ring.head)
    }

    @Test
    fun ringWriteGenauBisEnde() {
        val ring = ColumnRing(4)
        assertEquals(listOf(listOf(0, 4)), runs(ring.write(4)))
        assertEquals(0, ring.head)
    }

    @Test
    fun ringDrawSegmenteInAnzeigeReihenfolge() {
        val ring = ColumnRing(10)
        assertEquals(listOf(listOf(0, 10)), runs(ring.drawSegments()))
        ring.write(4)
        assertEquals(listOf(listOf(4, 6), listOf(0, 4)), runs(ring.drawSegments()))
    }

    @Test
    fun rangeRebuildNurBeiGrosserAenderung() {
        assertTrue(rangeNeedsRebuild(Float.NaN, Float.NaN, -80f, 0f))
        assertFalse(rangeNeedsRebuild(-80f, 0f, -79f, -0.5f))
        assertTrue(rangeNeedsRebuild(-80f, 0f, -77f, 0f))
        assertTrue(rangeNeedsRebuild(-80f, 0f, -80f, -2f))
    }

    @Test
    fun renderStatsLogtNachIntervall() {
        val stats = RenderStats(10_000L)
        var line: String? = null
        // 20 Zeichnungen pro Sekunde, 2 Spalten pro Zeichnung, 10 s lang
        for (i in 0..200) {
            line = stats.record(i * 50L, 1.5f, 2) ?: line
            if (i < 200) assertNull(line)
        }
        assertTrue(line!!.startsWith("T160_RENDER fps="))
        assertTrue(line.contains("frame_ms_median=1.50"))
    }

    @Test
    fun renderStatsPauseStartetFensterNeu() {
        val stats = RenderStats(10_000L)
        stats.record(0, 1f, 1)
        assertNull(stats.record(5_000, 1f, 1))
        // Pause > 2 s: Fenster beginnt neu, kein Log bei 12 s
        assertNull(stats.record(12_000, 1f, 1))
    }

    @Test
    fun snapshotLiefertNurNeueFrames() {
        val s = SpectrogramState(maxFrames = 100)
        s.appendFrames(List(5) { FloatArray(2) })
        val first = s.snapshotForRender(0, -1, 50, false)
        assertTrue(first.full)  // Struktur unbekannt -> Neuaufbau
        assertEquals(5, first.frames.size)
        s.appendFrames(List(3) { FloatArray(2) })
        val inc = s.snapshotForRender(first.appendedTotal, first.structureVersion, 50, false)
        assertFalse(inc.full)
        assertEquals(3, inc.frames.size)
        s.clear()
        assertTrue(s.snapshotForRender(inc.appendedTotal, inc.structureVersion, 50, false).full)
    }

    @Test
    fun snapshotNeuaufbauWennMehrAlsFensterbreiteNeu() {
        val s = SpectrogramState(maxFrames = 100)
        val a = s.snapshotForRender(0, 0, 10, false)
        s.appendFrames(List(25) { FloatArray(2) })
        val snap = s.snapshotForRender(a.appendedTotal, a.structureVersion, 10, false)
        assertTrue(snap.full)
        assertEquals(10, snap.frames.size)
    }
}
