package ch.etasystems.pirol.ui.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeZoomTest {
    @Test fun zoomInKeepsFocusRelative() {
        // Ausschnitt 100..200, Fokus bei 150 (Mitte) -> Breite 50, Start 125
        val (s, w) = TimeZoom.zoom(600f, 100f, 100f, 2f, 150f)
        assertEquals(50f, w, 1e-3f); assertEquals(125f, s, 1e-3f)
    }
    @Test fun zoomInAtLeftEdgeKeepsStart() {
        val (s, w) = TimeZoom.zoom(600f, 100f, 100f, 2f, 100f)
        assertEquals(100f, s, 1e-3f); assertEquals(50f, w, 1e-3f)
    }
    @Test fun zoomOutClampsToSession() {
        val (s, w) = TimeZoom.zoom(600f, 100f, 100f, 0.01f, 150f)
        assertEquals(600f, w, 1e-3f); assertEquals(0f, s, 1e-3f)
        assertTrue(TimeZoom.isWhole(600f, w))
    }
    @Test fun zoomInStopsAtOneWindow() {
        val (_, w) = TimeZoom.zoom(600f, 100f, 10f, 100f, 105f)
        assertEquals(TimeZoom.MIN_WIDTH_SEC, w, 1e-3f)
    }
    @Test fun shortSessionMinIsTotal() {
        val (s, w) = TimeZoom.zoom(2f, 0f, 2f, 5f, 1f)
        assertEquals(2f, w, 1e-3f); assertEquals(0f, s, 1e-3f)
    }
    @Test fun startStaysInRange() {
        val (s, w) = TimeZoom.zoom(600f, 560f, 40f, 0.5f, 599f)
        assertEquals(80f, w, 1e-3f); assertEquals(520f, s, 1e-3f)
        assertFalse(TimeZoom.isWhole(600f, w))
    }
    @Test fun invalidScaleIgnored() {
        assertEquals(10f to 30f, TimeZoom.zoom(600f, 10f, 30f, 0f, 20f))
    }
}
