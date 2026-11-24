package ch.etasystems.pirol.data.export

import ch.etasystems.pirol.data.repository.RecordingSegment
import ch.etasystems.pirol.data.repository.SessionMemo
import ch.etasystems.pirol.ml.DetectionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** T264: Session-Memos als eigene Raven-Zeilen (Species "Memo"). */
class RavenSessionMemoTest {

    private fun det(id: String, start: Float) = DetectionResult(
        id = id, scientificName = "Turdus merula", commonName = "Amsel", confidence = 0.8f,
        timestampMs = 1_000_000L, chunkStartSec = start, chunkEndSec = start + 3f, sampleRate = 48000
    )

    private fun memo(file: String, start: Float?, end: Float?) = SessionMemo(
        file = file, startedAt = "2026-11-24T10:00:00+01:00", endedAt = "2026-11-24T10:00:05+01:00",
        startSec = start, endSec = end, durationSec = 5f
    )

    private fun export(
        dets: List<DetectionResult>,
        memos: List<SessionMemo>,
        segments: List<RecordingSegment>? = null,
        fields: Set<String>? = null
    ): List<List<String>> {
        val f = File.createTempFile("raven", ".txt")
        try {
            RavenExporter.export(f, dets, "2026-11-24T10:00:00+01:00", recordingSegments = segments,
                enabledFields = fields, sessionMemos = memos)
            return f.readLines().map { it.split('\t') }
        } finally {
            f.delete()
        }
    }

    @Test
    fun memo_row_fields() {
        val rows = export(listOf(det("a", 10f)), listOf(memo("memos/session_20261124-100020.wav", 20f, 25f)))
        assertEquals(3, rows.size)
        val h = rows[0]
        val m = rows[2]
        assertEquals(h.size, m.size)
        assertEquals("2", m[0])
        assertEquals("recording.wav", m[h.indexOf("Begin File")])
        assertEquals("20.000", m[h.indexOf("Begin Time (s)")])
        assertEquals("25.000", m[h.indexOf("End Time (s)")])
        assertEquals("2026-11-24T10:00:20+01:00", m[h.indexOf("Begin Date Time")])
        assertEquals("Memo", m[h.indexOf("Species")])
        assertEquals("Memo", m[h.indexOf("Common Name")])
        assertEquals("", m[h.indexOf("Confidence")])
        assertEquals("", m[h.indexOf("Status")])
        assertEquals("", m[h.indexOf("Cluster")])
        assertEquals("memos/session_20261124-100020.wav", m[h.indexOf("Memo")])
    }

    @Test
    fun memo_sorted_by_begin_between_detections() {
        val rows = export(
            listOf(det("a", 10f), det("b", 40f)),
            listOf(memo("memos/m2.wav", 50f, 52f), memo("memos/m1.wav", 20f, 22f), memo("memos/m0.wav", 1f, 2f))
        )
        val sp = rows[0].indexOf("Species")
        val cl = rows[0].indexOf("Cluster")
        val order = rows.drop(1).map { if (it[sp] == "Memo") it[rows[0].indexOf("Memo")] else it[cl] }
        assertEquals(listOf("memos/m0.wav", "a", "memos/m1.wav", "b", "memos/m2.wav"), order)
        // fortlaufende Selection-Nummern
        assertEquals(listOf("1", "2", "3", "4", "5"), rows.drop(1).map { it[0] })
    }

    @Test
    fun memo_in_second_segment_uses_segment_file_and_local_time() {
        val segs = listOf(
            RecordingSegment("recording.wav", 0f, 3600f),
            RecordingSegment("recording-002.wav", 3600f, 1200f)
        )
        val rows = export(emptyList(), listOf(memo("memos/m.wav", 3700f, 3710f)), segs)
        val h = rows[0]
        assertEquals("recording-002.wav", rows[1][h.indexOf("Begin File")])
        assertEquals("100.000", rows[1][h.indexOf("Begin Time (s)")])
        assertEquals("110.000", rows[1][h.indexOf("End Time (s)")])
        assertEquals("2026-11-24T11:01:40+01:00", rows[1][h.indexOf("Begin Date Time")])
    }

    @Test
    fun memo_without_position_has_no_row() {
        val rows = export(listOf(det("a", 10f)), listOf(memo("memos/x.wav", null, null)))
        assertEquals(2, rows.size)
    }

    @Test
    fun memo_row_with_reduced_fields_has_header_width() {
        val rows = export(listOf(det("a", 10f)), listOf(memo("memos/m.wav", 5f, 6f)),
            fields = setOf(RavenExporter.FIELD_BEGIN_TIME, RavenExporter.FIELD_SPECIES))
        assertTrue(rows.drop(1).all { it.size == rows[0].size })
        assertEquals("Memo", rows[1][rows[0].indexOf("Species")])
    }

    @Test
    fun interleave_keeps_detection_order() {
        val out = RavenExporter.interleaveMemos(listOf(5f, 30f, 10f), { it }, listOf(memo("m", 20f, 21f)))
        // Memo vor der ersten spaeter beginnenden Zeile; Reihenfolge der uebrigen bleibt
        assertEquals(5f, out[0])
        assertTrue(out[1] is SessionMemo)
        assertEquals(listOf(30f, 10f), out.drop(2))
    }
}
