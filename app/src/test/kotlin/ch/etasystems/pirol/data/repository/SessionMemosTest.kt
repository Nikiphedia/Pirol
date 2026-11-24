package ch.etasystems.pirol.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** T264: Session-Memo: Dateiname, Verwerfen, Speichern und Lesen von memos.jsonl. */
class SessionMemosTest {

    private val zone = ZoneId.of("Europe/Zurich")
    private val t0 = ZonedDateTime.of(2026, 11, 24, 14, 30, 5, 0, zone).toInstant().toEpochMilli()

    @Test
    fun fileName_local_time_without_colon() {
        val name = SessionMemos.fileName(t0, zone) { false }
        assertEquals("session_20261124-143005.wav", name)
        assertFalse(name.contains(':'))
    }

    @Test
    fun fileName_collision_counts_up() {
        val taken = setOf("session_20261124-143005.wav", "session_20261124-143005-2.wav")
        assertEquals("session_20261124-143005-3.wav", SessionMemos.fileName(t0, zone) { it in taken })
        assertEquals("session_20261124-143005-2.wav", SessionMemos.fileName(t0, zone) { it == "session_20261124-143005.wav" })
    }

    @Test
    fun too_short_is_discarded() {
        assertTrue(SessionMemos.isTooShort(t0, t0 + 499))
        assertFalse(SessionMemos.isTooShort(t0, t0 + 500))
        assertTrue(SessionMemos.isTooShort(t0, t0))
    }

    @Test
    fun create_encode_parse_roundtrip() {
        val m = SessionMemos.create("session_20261124-143005.wav", t0, t0 + 12_300, 42.5f, zone)
        assertEquals("memos/session_20261124-143005.wav", m.file)
        assertEquals("2026-11-24T14:30:05+01:00", m.startedAt)
        assertEquals("2026-11-24T14:30:17.3+01:00", m.endedAt)
        assertEquals(42.5f, m.startSec!!, 1e-4f)
        assertEquals(54.8f, m.endSec!!, 1e-3f)
        assertEquals(12.3f, m.durationSec, 1e-4f)
        val line = SessionMemos.encode(m)
        assertTrue(line.endsWith("\n"))
        assertFalse(line.trimEnd().contains('\n'))
        val back = SessionMemos.parse(line + line)
        assertEquals(listOf(m, m), back)
        assertEquals(t0, back[0].startEpochMs)
        assertEquals(t0 + 12_300, back[0].endEpochMs)
    }

    @Test
    fun without_session_position_no_start_end() {
        val m = SessionMemos.create("x.wav", t0, t0 + 2000, null, zone)
        assertNull(m.startSec)
        assertNull(m.endSec)
        val back = SessionMemos.parse(SessionMemos.encode(m)).single()
        assertNull(back.startSec)
        assertEquals(2f, back.durationSec, 1e-4f)
    }

    @Test
    fun parse_skips_broken_lines_and_unknown_fields() {
        val text = """
            {"file":"memos/a.wav","startedAt":"2026-11-24T14:30:05+01:00","endedAt":"2026-11-24T14:30:09+01:00","startSec":1.0,"endSec":5.0,"durationSec":4.0,"extra":1}
            kaputt
            {"file":"memos/b.wav"}
        """.trimIndent()
        val memos = SessionMemos.parse(text)
        assertEquals(1, memos.size)
        assertEquals("memos/a.wav", memos[0].file)
        assertTrue(SessionMemos.parse(null).isEmpty())
        assertTrue(SessionMemos.parse("").isEmpty())
    }

    @Test
    fun memos_jsonl_is_exported() {
        assertFalse(SessionExportRules.isExcluded(SessionMemos.FILE))
    }
}
