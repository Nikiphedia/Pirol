package ch.etasystems.pirol.ui.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkerClusteringTest {

    @Test
    fun emptyInput_givesNoGroups() {
        assertTrue(MarkerClustering.groupByPixelDistance(DoubleArray(0), DoubleArray(0), 50.0).isEmpty())
    }

    @Test
    fun identicalPoints_formOneGroup() {
        val g = MarkerClustering.groupByPixelDistance(doubleArrayOf(5.0, 5.0, 5.0), doubleArrayOf(7.0, 7.0, 7.0), 50.0)
        assertEquals(listOf(listOf(0, 1, 2)), g)
    }

    @Test
    fun farPoints_stayApart() {
        val g = MarkerClustering.groupByPixelDistance(doubleArrayOf(0.0, 200.0), doubleArrayOf(0.0, 0.0), 50.0)
        assertEquals(listOf(listOf(0), listOf(1)), g)
    }

    @Test
    fun thresholdIsExclusive_andCrossesGridCells() {
        // Abstand 49 -> gruppiert (ueber Zellgrenze bei 50), Abstand 50 -> getrennt
        val near = MarkerClustering.groupByPixelDistance(doubleArrayOf(40.0, 89.0), doubleArrayOf(0.0, 0.0), 50.0)
        assertEquals(1, near.size)
        val edge = MarkerClustering.groupByPixelDistance(doubleArrayOf(40.0, 90.0), doubleArrayOf(0.0, 0.0), 50.0)
        assertEquals(2, edge.size)
    }

    @Test
    fun negativeCoordinates_work() {
        val g = MarkerClustering.groupByPixelDistance(doubleArrayOf(-10.0, -12.0), doubleArrayOf(-5.0, -6.0), 20.0)
        assertEquals(listOf(listOf(0, 1)), g)
    }

    @Test
    fun everyIndexAppearsExactlyOnce() {
        val n = 500
        val rnd = java.util.Random(42)
        val xs = DoubleArray(n) { rnd.nextDouble() * 1000 }
        val ys = DoubleArray(n) { rnd.nextDouble() * 1000 }
        val g = MarkerClustering.groupByPixelDistance(xs, ys, 60.0)
        assertEquals((0 until n).toList(), g.flatten().sorted())
    }

    @Test
    fun zoomDependent_samePointsMergeAtLowZoomSeparateAtHighZoom() {
        // zwei Punkte ca. 100 m auseinander
        val lats = doubleArrayOf(47.3769, 47.3778)
        val lons = doubleArrayOf(8.5417, 8.5417)
        val low = MarkerClustering.groupByZoom(lats, lons, 12.0, 100.0)
        val high = MarkerClustering.groupByZoom(lats, lons, 19.0, 100.0)
        assertEquals(1, low.size)
        assertEquals(2, high.size)
    }

    @Test
    fun mercatorMath_knownValues() {
        // Weltmitte bei Zoom 0
        assertEquals(128.0, MarkerClustering.mercatorX(0.0, 0.0), 1e-9)
        assertEquals(128.0, MarkerClustering.mercatorY(0.0, 0.0), 1e-9)
        // Zoom +1 verdoppelt Pixel
        assertEquals(2 * MarkerClustering.mercatorX(8.5, 5.0), MarkerClustering.mercatorX(8.5, 6.0), 1e-6)
    }
}
