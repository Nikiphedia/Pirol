package ch.etasystems.pirol.data.repository

import ch.etasystems.pirol.data.export.RavenExporter
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** T109: Session-Bemerkung - Dateiname, TSV-Escaping, JSON-Kompatibilitaet, Raven-Spalte. */
class SessionNoteTest {

    private val lenient = Json { ignoreUnknownKeys = true }
    private val id = "2026-11-18T10-06-57_7443c1"

    @Test
    fun `sanitize transliteriert Umlaute und ersetzt Sonderzeichen`() {
        assertEquals("Begehung-Verein-Aarschaecheli", SessionNoteUtil.sanitizeForFileName("Begehung Verein Aarschaecheli"))
        assertEquals("Gruen-Fuesse-Strasse", SessionNoteUtil.sanitizeForFileName("Grün, Füße / Straße"))
        assertEquals("Oe-Ae-Ue", SessionNoteUtil.sanitizeForFileName("Ö Ä Ü"))
        assertEquals("Cafe", SessionNoteUtil.sanitizeForFileName("Café"))
    }

    @Test
    fun `sanitize kuerzt auf 40 Zeichen und trimmt Bindestriche`() {
        val r = SessionNoteUtil.sanitizeForFileName("a".repeat(39) + " " + "b".repeat(30))
        assertTrue(r.length <= SessionNoteUtil.MAX_FILE_NAME_PART)
        assertFalse(r.endsWith("-"))
        assertEquals("", SessionNoteUtil.sanitizeForFileName("  ---  "))
        assertEquals("", SessionNoteUtil.sanitizeForFileName(null))
        assertEquals("", SessionNoteUtil.sanitizeForFileName("   "))
    }

    @Test
    fun `zipFileName mit und ohne Bemerkung`() {
        assertEquals("$id.zip", SessionNoteUtil.zipFileName(id, null))
        assertEquals("$id.zip", SessionNoteUtil.zipFileName(id, "  "))
        assertEquals("${id}_Begehung-Verein-Aarschaecheli.zip",
            SessionNoteUtil.zipFileName(id, "Begehung Verein Aarschaecheli"))
        assertTrue(SessionNoteUtil.zipFileName(id, "日本語 テスト").let { it == "$id.zip" })
    }

    @Test
    fun `escapeTsv ersetzt Tabs und Zeilenumbrueche`() {
        assertEquals("a b c d", RavenExporter.escapeTsv("a\tb\nc\r\nd".replace("\r\n", " ")))
        assertFalse(RavenExporter.escapeTsv("x\ty\nz").contains('\t'))
        assertFalse(RavenExporter.escapeTsv("x\ty\nz").contains('\n'))
    }

    private fun meta(note: String?) = SessionMetadata(
        sessionId = id, startedAt = "2026-11-18T11:06:57+01:00", sampleRate = 48000,
        confidenceThreshold = 0.5f, note = note
    )

    @Test
    fun `SessionMetadata Round-Trip mit note`() {
        val json = Json { prettyPrint = true }.encodeToString(meta("Begehung Verein"))
        assertEquals("Begehung Verein", lenient.decodeFromString<SessionMetadata>(json).note)
    }

    @Test
    fun `alte session json ohne note liefert null`() {
        val old = """{"sessionId":"$id","startedAt":"2026-11-18T11:06:57+01:00","sampleRate":48000,"confidenceThreshold":0.5}"""
        assertNull(lenient.decodeFromString<SessionMetadata>(old).note)
    }

    @Test
    fun `Raven Header enthaelt Session-Bemerkung nur mit Bemerkung als letzte Spalte`() {
        val f = File.createTempFile("raven", ".txt")
        try {
            RavenExporter.export(f, emptyList(), "2026-11-18T11:06:57+01:00", sessionNote = "Test\tNotiz")
            val header = f.readLines().first().split("\t")
            assertEquals("Session-Bemerkung", header.last())
            assertEquals("Corrected Species", header[header.size - 2])

            RavenExporter.export(f, emptyList(), "2026-11-18T11:06:57+01:00", sessionNote = "  ")
            assertFalse(f.readLines().first().contains("Session-Bemerkung"))
        } finally { f.delete() }
    }
}
