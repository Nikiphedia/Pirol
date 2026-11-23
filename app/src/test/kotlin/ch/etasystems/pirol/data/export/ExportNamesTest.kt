package ch.etasystems.pirol.data.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class ExportNamesTest {
    private val zurich = TimeZone.getTimeZone("Europe/Zurich")
    // 2026-04-20T12:30:00Z = 14:30 Lokalzeit (CEST)
    private val ts = 1776688200000L

    @Test fun `Detektion mit Bemerkung`() {
        assertEquals("20260420143000_Amsel_Garten_hinten",
            ExportNames.detectionName(ts, "Amsel", "Garten hinten", zurich))
    }

    @Test fun `Detektion ohne Bemerkung kein Unterstrich am Ende`() {
        assertEquals("20260420143000_Amsel", ExportNames.detectionName(ts, "Amsel", null, zurich))
        assertEquals("20260420143000_Amsel", ExportNames.detectionName(ts, "Amsel", "  ", zurich))
    }

    @Test fun `Zeit ist lokal`() {
        assertEquals("20260420123000", ExportNames.timePart(ts, TimeZone.getTimeZone("UTC")))
        assertEquals("20260420143000", ExportNames.timePart(ts, zurich))
    }

    @Test fun `Sonderzeichen und Umlaute`() {
        assertEquals("Grün_Füsse", ExportNames.sanitizePart("Grün Füsse", 40))
        assertEquals("a_b_c_d", ExportNames.sanitizePart("a/b:c*?\"d", 40))
        assertEquals("Wald_Weg", ExportNames.sanitizePart("  Wald  /  Weg ", 40))
        assertEquals("x", ExportNames.sanitizePart("..x..", 40))
        assertEquals("", ExportNames.sanitizePart("///", 40))
    }

    @Test fun `Laenge begrenzt`() {
        val r = ExportNames.sanitizePart("a".repeat(39) + " " + "b".repeat(30), ExportNames.MAX_NOTE_LENGTH)
        assertTrue(r.length <= 40)
        assertTrue(!r.endsWith("_"))
    }

    @Test fun `Session mit und ohne Bemerkung`() {
        val s = "2026-04-20T14:30:00+02:00"
        assertEquals("20260420143000_Begehung_Aare", ExportNames.sessionName(s, "Begehung Aare", "x"))
        assertEquals("20260420143000", ExportNames.sessionName(s, null, "x"))
        assertEquals("20260420143000.zip", ExportNames.sessionZipName(s, "", "x"))
    }

    @Test fun `Session Zeit bleibt Lokalzeit des Offsets`() {
        assertEquals("20260420233000", ExportNames.sessionName("2026-04-20T23:30:00-07:00", null, "x"))
    }

    @Test fun `Session unlesbare Zeit nutzt Id`() {
        assertEquals("abc_Notiz", ExportNames.sessionName("kaputt", "Notiz", "abc"))
    }

    @Test fun `Kollision bekommt Suffix`() {
        val taken = setOf("n.zip", "n-2.zip")
        assertEquals("n-3.zip", ExportNames.uniqueName("n", "zip") { it in taken })
        assertEquals("m.zip", ExportNames.uniqueName("m", "zip") { it in taken })
    }

    @Test fun `zwei Sessions gleicher Minute bekommen verschiedene Namen, Retry denselben`() {
        val base = ExportNames.sessionName("2026-04-20T14:30:10+02:00", null, "a")
        val remote = mutableSetOf<String>()
        val first = ExportNames.chooseZipName(null, base) { it in remote }
        remote += first
        val second = ExportNames.chooseZipName(null, base) { it in remote }
        assertEquals("20260420143010.zip", first)
        assertEquals("20260420143010-2.zip", second)
        // Retry der zweiten Session: gemerkter Name, obwohl er schon (teilweise) existiert
        remote += second
        assertEquals(second, ExportNames.chooseZipName(second, base) { it in remote })
    }

    @Test fun `reservierte Namen anderer Sessions gelten als belegt`() {
        val base = "20260420143010"
        val reserved = setOf("$base.zip")
        assertEquals("$base-2.zip", ExportNames.chooseZipName(null, base, reserved) { false })
        // eigener gemerkter Name bleibt
        assertEquals("$base.zip", ExportNames.chooseZipName("$base.zip", base, reserved) { true })
    }
}
