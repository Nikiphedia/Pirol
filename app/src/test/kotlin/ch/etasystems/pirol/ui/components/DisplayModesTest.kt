package ch.etasystems.pirol.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T193: reine Funktionen von Oszillogramm und Spektrum. */
class DisplayModesTest {

    @Test
    fun columnMinMaxVerdichtetBins() {
        val mn = floatArrayOf(-0.1f, -0.5f, -0.2f, -0.3f)
        val mx = floatArrayOf(0.1f, 0.2f, 0.9f, 0.3f)
        val om = FloatArray(2); val ox = FloatArray(2)
        columnMinMax(mn, mx, 4, 4, 2, om, ox)
        assertEquals(-0.5f, om[0], 0f); assertEquals(0.2f, ox[0], 0f)
        assertEquals(-0.3f, om[1], 0f); assertEquals(0.9f, ox[1], 0f)
    }

    @Test
    fun columnMinMaxRechtsbuendigBeiWenigDaten() {
        val mn = floatArrayOf(-0.4f, -0.2f)
        val mx = floatArrayOf(0.4f, 0.2f)
        val om = FloatArray(4); val ox = FloatArray(4)
        columnMinMax(mn, mx, 2, 4, 4, om, ox)
        assertFalse(columnHasData(om[0], ox[0]))
        assertFalse(columnHasData(om[1], ox[1]))
        assertEquals(0.4f, ox[2], 0f)
        assertEquals(0.2f, ox[3], 0f)
    }

    @Test
    fun columnMinMaxMehrSpaltenAlsBins() {
        val om = FloatArray(8); val ox = FloatArray(8)
        columnMinMax(floatArrayOf(-1f, -2f), floatArrayOf(1f, 2f), 2, 2, 8, om, ox)
        assertTrue((0 until 8).all { columnHasData(om[it], ox[it]) })
        assertEquals(1f, ox[0], 0f); assertEquals(2f, ox[7], 0f)
    }

    @Test
    fun waveformStateBinsUndRing() {
        val ws = WaveformState(maxBins = 3, binSize = 2)
        ws.append(shortArrayOf(0, 16384, -16384, 0, 100))  // 2 Bins + 1 Rest
        assertEquals(2, ws.size)
        ws.append(shortArrayOf(0, 1, 2, 3, 4, 5))
        assertEquals(3, ws.size)  // Ring voll
        val a = FloatArray(3); val b = FloatArray(3)
        assertEquals(3, ws.copyLast(3, a, b))
        assertEquals(100 / 32768f, b[0], 1e-6f)  // aeltestes verbleibendes Bin: (100, 0), Max = 100
        ws.clear()
        assertEquals(0, ws.size)
    }

    @Test
    fun niceAmplitude() {
        assertEquals(0.01f, niceAmplitudeRange(0f), 0f)
        assertEquals(0.05f, niceAmplitudeRange(0.03f), 0f)
        assertEquals(0.5f, niceAmplitudeRange(0.5f), 0f)
        assertEquals(1f, niceAmplitudeRange(0.7f), 0f)
        assertEquals(1f, niceAmplitudeRange(5f), 0f)
        assertEquals("0.05", amplitudeLabel(0.05f))
        assertEquals("-0.5", amplitudeLabel(-0.5f))
    }

    @Test
    fun dbTicksSchritte() {
        assertEquals(listOf(-80, -60, -40, -20, 0), dbTicks(-80f, 0f))
        assertEquals(listOf(-50, -40, -30, -20), dbTicks(-55f, -20f))
    }
}
