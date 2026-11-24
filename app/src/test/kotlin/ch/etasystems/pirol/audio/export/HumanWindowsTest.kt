package ch.etasystems.pirol.audio.export

import ch.etasystems.pirol.data.repository.SessionExportRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T261: Merkliste, Abschnitte (vereinigen + Rand), Luecken, Scan-Fenster, Ausschlussregel. */
class HumanWindowsTest {

    private fun human(a: Float, b: Float, s: Float) = HumanWindowLine(HumanWindows.KIND_HUMAN, a, b, s)
    private fun scanned(a: Float, b: Float) = HumanWindowLine(HumanWindows.KIND_SCANNED, a, b)

    @Test
    fun abschnitte_vereinigen_mit_rand_und_schwelle() {
        val lines = listOf(
            human(3f, 6f, 0.4f), human(6f, 9f, 0.2f),   // zusammenhaengend
            human(20f, 23f, 0.05f),                     // unter Schwelle
            human(30f, 33f, 0.5f), human(33.5f, 36.5f, 0.5f),  // Luecke 0.5 s < 2x Rand -> vereinigt
            human(98f, 101f, 0.9f)                      // ueber das Ende hinaus
        )
        val s = HumanWindows.sections(lines, 0.10f, totalSec = 100f)
        assertEquals(listOf(TimeSpan(2.5f, 9.5f), TimeSpan(29.5f, 37f), TimeSpan(97.5f, 100f)), s)
    }

    @Test
    fun luecken_aus_scanned() {
        val lines = listOf(scanned(0f, 30f), scanned(30.02f, 60f), scanned(90f, 120f), human(5f, 8f, 0.9f))
        assertEquals(listOf(TimeSpan(60f, 90f), TimeSpan(120f, 125f)), HumanWindows.gaps(lines, 125f))
        assertEquals(listOf(TimeSpan(0f, 10f)), HumanWindows.gaps(emptyList(), 10f))
        assertTrue(HumanWindows.gaps(listOf(scanned(0f, 10f)), 10f).isEmpty())
    }

    @Test
    fun scan_fenster_decken_luecke_ab() {
        assertEquals(listOf(60f, 63f, 66f, 67f), HumanWindows.scanStarts(TimeSpan(60f, 70f), 3f))
        assertEquals(listOf(0f), HumanWindows.scanStarts(TimeSpan(0f, 1f), 3f))  // nie vor 0
        assertEquals(listOf(7f), HumanWindows.scanStarts(TimeSpan(9f, 10f), 3f))
    }

    @Test
    fun collector_vereinigt_bereiche_und_merkt_ab_untergrenze() {
        val c = HumanWindowCollector("m")
        c.add(0f, 3f, 0.0f)
        c.add(3f, 6f, 0.5f)
        c.add(6f, 9f, null)          // Modell ohne Mensch-Klasse: nicht als bewertet
        c.add(12f, 15f, 0.01f)       // Luecke 6-12
        val first = c.drain()
        assertEquals(listOf(human(3f, 6f, 0.5f).copy(model = "m"),
            HumanWindowLine(HumanWindows.KIND_SCANNED, 0f, 6f, model = "m"),
            HumanWindowLine(HumanWindows.KIND_SCANNED, 12f, 15f, model = "m")), first)
        c.add(15f, 18f, 0f)
        assertEquals(listOf(HumanWindowLine(HumanWindows.KIND_SCANNED, 15f, 18f, model = "m")), c.drain())
        val parsed = HumanWindows.parse(HumanWindows.encode(first) + "{kaputt\n")
        assertEquals(first, parsed)
        assertEquals(listOf(TimeSpan(6f, 12f)), HumanWindows.gaps(parsed, 15f))
    }

    @Test
    fun frames_und_schnitt() {
        val f = HumanWindows.toFrames(listOf(TimeSpan(0.5f, 1.25f), TimeSpan(9.5f, 12f)), 1000, 10_000)
        assertEquals(listOf(FrameSpan(500, 1250), FrameSpan(9500, 10_000)), f)
        assertTrue(HumanWindows.intersects(f, FrameSpan(1200, 1300)))
        assertFalse(HumanWindows.intersects(f, FrameSpan(1250, 9500)))
    }

    @Test
    fun merkliste_wird_nicht_exportiert() {
        assertTrue(SessionExportRules.isExcluded(HumanWindows.FILE))
        assertFalse(SessionExportRules.isExcluded("recording.wav"))
    }
}
