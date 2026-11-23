package ch.etasystems.pirol.audio.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** T201: rollende Peak-Spuren des Spektrums. */
class SpectrumTraceBufferTest {

    private fun frame(v: Float, n: Int = 4) = FloatArray(n) { v }

    @Test
    fun `Maximum je Scheibe`() {
        val b = SpectrumTraceBuffer(sliceSec = 1f)
        b.setDuration(5f)
        b.add(floatArrayOf(-50f, -30f, -60f), 0.3f)
        b.add(floatArrayOf(-40f, -35f, -20f), 0.3f)
        b.add(floatArrayOf(-45f, -90f, -70f), 0.5f)  // schliesst die Scheibe (1.1 s)
        val layers = b.layers()
        assertEquals(1, layers.size)
        assertEquals(listOf(-40f, -30f, -20f), layers[0].values.toList())
    }

    @Test
    fun `Alpha sinkt linear mit dem Alter, juengste zuerst`() {
        val b = SpectrumTraceBuffer(sliceSec = 1f)
        b.setDuration(3f)  // 3 abgeschlossene Scheiben + offene = 4 Schichten
        for (i in 1..3) b.add(frame(-10f * i), 1f)
        b.add(frame(-5f), 0.1f)  // offene Scheibe
        val l = b.layers()
        assertEquals(4, l.size)
        assertEquals(-5f, l[0].values[0], 0f)   // offene = juengste
        assertEquals(-30f, l[1].values[0], 0f)  // dann die juengste geschlossene
        assertEquals(-10f, l[3].values[0], 0f)  // aelteste
        assertEquals(1f, l[0].alpha, 1e-5f)
        assertEquals(0.75f, l[1].alpha, 1e-5f)
        assertEquals(0.5f, l[2].alpha, 1e-5f)
        assertEquals(0.25f, l[3].alpha, 1e-5f)
        assertTrue(l.all { it.alpha > 0f })
    }

    @Test
    fun `aeltere Scheiben als N s werden verworfen`() {
        val b = SpectrumTraceBuffer(sliceSec = 1f)
        b.setDuration(2f)
        for (i in 1..6) b.add(frame(-10f * i), 1f)
        val l = b.layers()
        assertEquals(2, l.size)
        assertEquals(-60f, l[0].values[0], 0f)
        assertEquals(-50f, l[1].values[0], 0f)
        // kuerzere Dauer verwirft sofort
        b.setDuration(1f)
        assertEquals(1, b.layers().size)
    }

    @Test
    fun `Dauer Aus leert und sammelt nicht`() {
        val b = SpectrumTraceBuffer(sliceSec = 1f)
        b.setDuration(5f)
        b.add(frame(-10f), 1f)
        assertEquals(1, b.layers().size)
        b.setDuration(0f)
        assertTrue(b.layers().isEmpty())
        b.add(frame(-10f), 1f)
        assertTrue(b.layers().isEmpty())
    }

    @Test
    fun `Speicher ist auf 60 Scheiben begrenzt`() {
        val b = SpectrumTraceBuffer()
        b.setDuration(1000f)
        for (i in 0 until 500) b.add(frame(-30f), 0.5f)
        b.add(frame(-30f), 0.1f)  // offene Scheibe
        assertEquals(SpectrumTraceBuffer.MAX_SLICES, b.layers().size - 1)
    }

    @Test
    fun `neue Frame-Groesse verwirft alte Schichten`() {
        val b = SpectrumTraceBuffer(sliceSec = 1f)
        b.setDuration(5f)
        b.add(frame(-10f, 4), 1f)
        b.add(frame(-10f, 8), 0.1f)
        val l = b.layers()
        assertEquals(1, l.size)
        assertEquals(8, l[0].values.size)
    }
}
