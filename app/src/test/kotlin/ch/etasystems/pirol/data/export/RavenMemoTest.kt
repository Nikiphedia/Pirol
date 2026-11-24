package ch.etasystems.pirol.data.export

import ch.etasystems.pirol.ml.DetectionResult
import ch.etasystems.pirol.ui.analysis.DetectionOccurrences.MergeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** T263: Raven-Spalte "Memo" (hinter Windows, vor Session-Bemerkung, auf jeder Episoden-Zeile). */
class RavenMemoTest {

    private fun det(id: String, memo: String?) = DetectionResult(
        id = id, scientificName = "Turdus merula", commonName = "Amsel", confidence = 0.8f,
        timestampMs = 1_000_000L, chunkStartSec = 10f, chunkEndSec = 13f, sampleRate = 48000,
        voiceMemoFile = memo
    )

    private fun merge(start: Float) = MergeInfo(timestampMs = 1_000_000L + ((start - 10f) * 1000).toLong(), confidence = 0.5f, startSec = start)

    private fun export(
        dets: List<DetectionResult>,
        fields: Set<String>? = null,
        merges: Map<String, List<MergeInfo>> = emptyMap(),
        note: String? = null
    ): List<List<String>> {
        val f = File.createTempFile("raven", ".txt")
        try {
            RavenExporter.export(f, dets, "2026-11-23T10:00:00+01:00", enabledFields = fields, sessionNote = note, merges = merges)
            return f.readLines().map { it.split('\t') }
        } finally {
            f.delete()
        }
    }

    @Test
    fun memo_column_between_windows_and_note() {
        val rows = export(listOf(det("a", "memos/a.wav")), note = "Test")
        val h = rows[0]
        val w = h.indexOf("Windows")
        assertEquals("Memo", h[w + 1])
        assertEquals("Session-Bemerkung", h[w + 2])
        assertEquals("memos/a.wav", rows[1][w + 1])
        assertEquals(h.size, rows[1].size)
    }

    @Test
    fun without_memo_cell_is_empty_but_column_present() {
        val rows = export(listOf(det("a", null), det("b", "memos/b.wav")))
        val i = rows[0].indexOf("Memo")
        assertTrue(i > 0)
        assertEquals("", rows[1][i])
        assertEquals("memos/b.wav", rows[2][i])
        assertEquals(rows[0].size, rows[1].size)
    }

    @Test
    fun memo_on_every_episode_row_of_a_detection() {
        // Episode 1: 10-13; Episode 2 (Luecke > Fensterlaenge): 30-33
        val rows = export(listOf(det("a", "memos/a.wav")), merges = mapOf("a" to listOf(merge(30f))))
        assertEquals(3, rows.size)
        val i = rows[0].indexOf("Memo")
        assertEquals("memos/a.wav", rows[1][i])
        assertEquals("memos/a.wav", rows[2][i])
    }

    @Test
    fun field_off_no_column() {
        val rows = export(listOf(det("a", "memos/a.wav")), fields = setOf(RavenExporter.FIELD_SPECIES))
        assertFalse(rows[0].contains("Memo"))
        assertEquals(rows[0].size, rows[1].size)
    }

    @Test
    fun memo_field_is_part_of_all_fields() {
        assertTrue(RavenExporter.FIELD_MEMO in RavenExporter.ALL_FIELDS)
        assertEquals("pirol_raven_field_memo", RavenExporter.FIELD_MEMO)
        assertTrue(RavenExporter.FIELD_MEMO in RavenExporter.enabledFields { true })
        assertFalse(RavenExporter.FIELD_MEMO in RavenExporter.enabledFields { it != RavenExporter.FIELD_MEMO })
    }
}
