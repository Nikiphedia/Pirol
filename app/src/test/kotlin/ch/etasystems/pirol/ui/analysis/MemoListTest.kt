package ch.etasystems.pirol.ui.analysis

import ch.etasystems.pirol.audio.export.MemoExclusion.WallSpan
import ch.etasystems.pirol.data.repository.SessionMemo
import ch.etasystems.pirol.ml.DetectionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** T264: Liste des Memo-Blocks (Session- und Art-Memos gemischt, chronologisch, fehlende Zeiten). */
class MemoListTest {

    private val zone = ZoneId.of("Europe/Zurich")
    private fun at(h: Int, m: Int, s: Int) = ZonedDateTime.of(2026, 11, 24, h, m, s, 0, zone).toInstant().toEpochMilli()

    private fun det(id: String, ts: Long, memo: String?, corrected: String? = null) = DetectionResult(
        id = id, scientificName = "Turdus merula", commonName = "Amsel", confidence = 0.8f,
        timestampMs = ts, chunkStartSec = 0f, chunkEndSec = 3f, sampleRate = 48000,
        voiceMemoFile = memo, correctedSpecies = corrected
    )

    private fun sm(file: String, start: String, end: String, startSec: Float? = 5f) = SessionMemo(
        file = file, startedAt = start, endedAt = end, startSec = startSec, endSec = startSec?.plus(4f), durationSec = 4f
    )

    @Test
    fun mixed_sorted_by_start() {
        val items = MemoList.build(
            sessionMemos = listOf(
                sm("memos/s2.wav", "2026-11-24T10:20:00+01:00", "2026-11-24T10:20:04+01:00"),
                sm("memos/s1.wav", "2026-11-24T10:05:00+01:00", "2026-11-24T10:05:04+01:00")
            ),
            detections = listOf(
                det("a", at(10, 10, 0), "memos/a.wav"),
                det("b", at(10, 1, 0), null),                   // ohne Memo: nicht in der Liste
                det("c", at(10, 30, 0), "memos/c.wav")
            ),
            artSpans = mapOf("a" to WallSpan(at(10, 12, 0), at(10, 12, 9)))
        )
        assertEquals(listOf("session:memos/s1.wav", "a", "session:memos/s2.wav", "c"), items.map { it.key })
        assertTrue(items[0].isSessionMemo)
        assertFalse(items[1].isSessionMemo)
        assertEquals(5f, items[0].sessionStartSec!!, 1e-4f)
    }

    @Test
    fun art_memo_with_span_has_from_to() {
        val items = MemoList.build(emptyList(), listOf(det("a", at(10, 0, 0), "memos/a.wav")),
            mapOf("a" to WallSpan(at(10, 12, 0), at(10, 12, 9))))
        val a = items.single()
        assertFalse(a.approximate)
        assertEquals("10:12:00–10:12:09", MemoList.timeLabel(a, zone))
        assertEquals("memos/a.wav", a.file)
        assertEquals("a", a.detectionId)
    }

    @Test
    fun art_memo_without_span_uses_detection_time_marked() {
        val a = MemoList.build(emptyList(), listOf(det("a", at(9, 59, 30), "memos/a.wav")), emptyMap()).single()
        assertTrue(a.approximate)
        assertEquals("09:59:30", MemoList.timeLabel(a, zone))
    }

    @Test
    fun corrected_species_is_shown() {
        val a = MemoList.build(emptyList(), listOf(det("a", at(9, 0, 0), "memos/a.wav", corrected = "Turdus philomelos")), emptyMap()).single()
        assertEquals("Turdus philomelos", a.scientificName)
    }

    @Test
    fun session_memo_label_and_unreadable_time_last() {
        val items = MemoList.build(
            listOf(sm("memos/x.wav", "kaputt", "kaputt", null), sm("memos/y.wav", "2026-11-24T08:00:00+01:00", "2026-11-24T08:00:30+01:00")),
            emptyList(), emptyMap()
        )
        assertEquals(listOf("session:memos/y.wav", "session:memos/x.wav"), items.map { it.key })
        assertEquals("08:00:00–08:00:30", MemoList.timeLabel(items[0], zone))
        assertEquals("", MemoList.timeLabel(items[1], zone))
    }
}
