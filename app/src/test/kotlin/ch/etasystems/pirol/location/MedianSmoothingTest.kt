package ch.etasystems.pirol.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit-Tests fuer Median-Smoothing (T53).
 * Prueft medianOf() aus LocationProvider.kt (internal fun).
 */
class MedianSmoothingTest {

    @Test
    fun `median von 5 Werten mit Ausreisser`() {
        // [1,2,3,100,4] sortiert → [1,2,3,4,100] → median = 3
        assertEquals(3.0, medianOf(listOf(1.0, 2.0, 3.0, 100.0, 4.0)), 0.0001)
    }

    @Test
    fun `median von 4 Werten (gerade Anzahl)`() {
        // [1,2,100,4] sortiert → [1,2,4,100] → median = (2+4)/2 = 3
        assertEquals(3.0, medianOf(listOf(1.0, 2.0, 100.0, 4.0)), 0.0001)
    }

    @Test
    fun `median von einzelnem Wert`() {
        assertEquals(42.0, medianOf(listOf(42.0)), 0.0001)
    }

    @Test
    fun `median ignoriert Afrika-Ausreisser (0_0 Sprung)`() {
        // Window: 4 plausible CH-Koordinaten + 1 Ausreisser (0/0)
        val lats = listOf(47.38, 47.381, 47.379, 47.382, 0.0)
        val result = medianOf(lats)
        // Median der sortierten Liste [0.0, 47.379, 47.38, 47.381, 47.382] = 47.38
        assertTrue("Median sollte > 40 sein, war $result", result > 40.0)
    }

    @Test
    fun `median von 2 Werten`() {
        // [3.0, 7.0] → median = (3+7)/2 = 5
        assertEquals(5.0, medianOf(listOf(3.0, 7.0)), 0.0001)
    }

    @Test
    fun `median ist stabil bei identischen Werten`() {
        assertEquals(47.38, medianOf(listOf(47.38, 47.38, 47.38)), 0.0001)
    }
}
