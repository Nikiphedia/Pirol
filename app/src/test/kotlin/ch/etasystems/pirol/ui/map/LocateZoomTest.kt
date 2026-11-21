package ch.etasystems.pirol.ui.map

import kotlin.math.cos
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Test

class LocateZoomTest {
    @Test
    fun heightIsAbout200Meters() {
        val lat = 47.4
        val zoom = LocateZoom.zoomForHeight(lat, 1000, 22.0)
        val metersPerPx = 156543.03392 * cos(Math.toRadians(lat)) / 2.0.pow(zoom)
        assertEquals(200.0, metersPerPx * 1000, 0.5)
    }

    @Test
    fun fallbackWhenNoLayout() {
        assertEquals(18.0, LocateZoom.zoomForHeight(47.4, 0, 22.0), 0.0)
    }

    @Test
    fun respectsMaxZoom() {
        assertEquals(17.0, LocateZoom.zoomForHeight(47.4, 5000, 17.0), 0.0)
    }
}
