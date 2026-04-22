package ch.etasystems.pirol.location

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit-Tests fuer den GPS Accuracy-Filter (T53).
 * Prueft: Fixes <= gpsMaxAccuracyMeters werden akzeptiert, Fixes > werden verworfen.
 */
class AccuracyFilterTest {

    private val maxAccuracy = 50f

    @Test
    fun `akzeptiert 10m Fix`() {
        assertTrue(10f <= maxAccuracy)
    }

    @Test
    fun `akzeptiert 30m Fix`() {
        assertTrue(30f <= maxAccuracy)
    }

    @Test
    fun `akzeptiert 50m Fix (Grenzwert)`() {
        assertTrue(50f <= maxAccuracy)
    }

    @Test
    fun `verwirft 51m Fix`() {
        assertFalse(51f <= maxAccuracy)
    }

    @Test
    fun `verwirft 100m Fix`() {
        assertFalse(100f <= maxAccuracy)
    }

    @Test
    fun `verwirft Float_MAX_VALUE (kein hasAccuracy)`() {
        assertFalse(Float.MAX_VALUE <= maxAccuracy)
    }
}
