package ch.etasystems.pirol.ui

import ch.etasystems.pirol.ui.analysis.OverviewBarGeometry
import ch.etasystems.pirol.ui.analysis.RavenTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T194: TSV-Parser der Raven-Tabelle und Geometrie der Uebersichtsleiste. */
class RavenTableTest {

    @Test fun parse_tabsEmptyFieldsUtf8() {
        val t = RavenTable.parse(
            "Selection\tSpecies\tNote\tConf\r\n1\tTurdus merula\t\t0.912\r\n2\tSchwarzkehlchen Müller\tbei Bach\t0.5\r\n"
        )!!
        assertEquals(listOf("Selection", "Species", "Note", "Conf"), t.header)
        assertEquals(2, t.rows.size)
        assertEquals(listOf("1", "Turdus merula", "", "0.912"), t.rows[0])  // leeres Feld bleibt
        assertEquals("Schwarzkehlchen Müller", t.rows[1][1])
        assertEquals(setOf(0, 3), t.numericColumns)
    }

    @Test fun parse_shortRowPaddedAndBomStripped() {
        val t = RavenTable.parse("\uFEFFA\tB\tC\n1\t2\n")!!
        assertEquals("A", t.header[0])
        assertEquals(listOf("1", "2", ""), t.rows[0])
    }

    @Test fun parse_emptyTextIsNull() {
        assertNull(RavenTable.parse(""))
    }

    @Test fun numericColumn_needsValues() {
        val t = RavenTable.parse("A\tB\n\tx\n\ty\n")!!
        assertTrue(0 !in t.numericColumns)
    }

    @Test fun columnWidths_clamped() {
        val t = RavenTable.parse("A\tLang\n1\t${"x".repeat(100)}\n")!!
        val w = RavenTable.columnWidthsDp(t)
        assertEquals(64, w[0])
        assertEquals(240, w[1])
    }

    @Test fun rect_fromWindow() {
        val (l, r) = OverviewBarGeometry.windowFractions(100f, 25f, 50f)
        assertEquals(0.25f, l, 1e-4f)
        assertEquals(0.5f, r, 1e-4f)
    }

    @Test fun rect_minWidthAndFullView() {
        val (l, r) = OverviewBarGeometry.windowFractions(1000f, 500f, 500.1f)
        assertTrue(r - l >= OverviewBarGeometry.MIN_RECT_FRACTION - 1e-5f)
        assertEquals(0f to 1f, OverviewBarGeometry.windowFractions(100f, 0f, 100f))
        val (l2, r2) = OverviewBarGeometry.windowFractions(1000f, 999.99f, 1000f)
        assertTrue(r2 <= 1f && l2 >= 0f && r2 - l2 >= OverviewBarGeometry.MIN_RECT_FRACTION - 1e-5f)
    }

    @Test fun tap_centersAndClamps() {
        assertEquals(40f, OverviewBarGeometry.startForCenter(100f, 20f, 0.5f), 1e-4f)
        assertEquals(0f, OverviewBarGeometry.startForCenter(100f, 20f, 0.02f), 1e-4f)
        assertEquals(80f, OverviewBarGeometry.startForCenter(100f, 20f, 1f), 1e-4f)
    }

    @Test fun drag_movesAndClamps() {
        assertEquals(30f, OverviewBarGeometry.startAfterDrag(100f, 20f, 20f, 0.1f), 1e-4f)
        assertEquals(0f, OverviewBarGeometry.startAfterDrag(100f, 20f, 5f, -0.5f), 1e-4f)
        assertEquals(80f, OverviewBarGeometry.startAfterDrag(100f, 20f, 70f, 0.5f), 1e-4f)
    }

    @Test fun windowWidth_defaultAndCap() {
        assertEquals(30f, OverviewBarGeometry.windowWidth(100f, null), 1e-4f)
        assertEquals(10f, OverviewBarGeometry.windowWidth(10f, null), 1e-4f)
        assertEquals(20f, OverviewBarGeometry.windowWidth(100f, 20f), 1e-4f)
    }
}
