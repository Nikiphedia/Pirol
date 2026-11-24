package ch.etasystems.pirol.audio.export

import ch.etasystems.pirol.audio.export.MemoExclusion.WallSpan
import ch.etasystems.pirol.ml.VerificationEvent
import ch.etasystems.pirol.ml.VerificationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** T263: Memo-Zeitraum von den Mensch-Abschnitten abziehen. */
class MemoExclusionTest {

    private fun ev(id: String, action: String, s: Long? = null, e: Long? = null) = VerificationEvent(
        detectionId = id, status = VerificationStatus.UNVERIFIED, verifiedAtMs = 0L,
        actionType = action, newValue = "memos/$id.wav", memoStartMs = s, memoEndMs = e
    )

    @Test
    fun wallSpans_recorded_deleted_unknown() {
        val spans = MemoExclusion.wallSpans(
            listOf(
                ev("a", VerificationEvent.ACTION_MEMO_RECORDED, 1000, 5000),
                ev("b", VerificationEvent.ACTION_MEMO_RECORDED, 9000, 12000),
                ev("b", VerificationEvent.ACTION_MEMO_DELETED),
                ev("c", VerificationEvent.ACTION_MEMO_RECORDED),          // Zeit unbekannt (Analyse/alt)
                ev("d", VerificationEvent.ACTION_MEMO_RECORDED, 7000, 7000) // leer
            )
        )
        assertEquals(listOf(WallSpan(1000, 5000)), spans)
    }

    @Test
    fun toSession_with_margin_and_clipping() {
        // Session-Achse beginnt bei Wanduhr 100_000; Memo 110 s..120 s Wanduhr = 10..20 s
        val r = MemoExclusion.toSessionSpans(listOf(WallSpan(110_000, 120_000)), 100_000, 60f)
        assertEquals(listOf(TimeSpan(9.5f, 20.5f)), r)
        // Am Anfang: Rand wird bei 0 begrenzt
        val r0 = MemoExclusion.toSessionSpans(listOf(WallSpan(100_200, 102_000)), 100_000, 60f)
        assertEquals(0f, r0[0].startSec, 1e-4f)
    }

    @Test
    fun toSession_memo_after_recording_end_or_unknown_clock_is_ignored() {
        // Memo erst nach Aufnahmeende (Analyse): 200 s nach Start bei 60 s Aufnahme
        assertTrue(MemoExclusion.toSessionSpans(listOf(WallSpan(300_000, 310_000)), 100_000, 60f).isEmpty())
        // Memo vor Aufnahmebeginn
        assertTrue(MemoExclusion.toSessionSpans(listOf(WallSpan(50_000, 60_000)), 100_000, 60f).isEmpty())
        // Wanduhr der Achse unbekannt: nichts abziehen
        assertTrue(MemoExclusion.toSessionSpans(listOf(WallSpan(110_000, 120_000)), null, 60f).isEmpty())
        // Unplausibel lang
        assertTrue(MemoExclusion.toSessionSpans(listOf(WallSpan(110_000, 200_000)), 100_000, 600f).isEmpty())
    }

    @Test
    fun subtract_overlap_inside_and_outside() {
        val sections = listOf(TimeSpan(10f, 20f), TimeSpan(40f, 50f), TimeSpan(70f, 80f))
        val cuts = listOf(TimeSpan(15f, 25f), TimeSpan(42f, 48f), TimeSpan(90f, 95f))
        val r = MemoExclusion.subtract(sections, cuts)
        assertEquals(
            listOf(TimeSpan(10f, 15f), TimeSpan(40f, 42f), TimeSpan(48f, 50f), TimeSpan(70f, 80f)),
            r
        )
    }

    @Test
    fun subtract_fully_covered_section_vanishes_and_empty_cuts_keep_all() {
        val s = listOf(TimeSpan(10f, 20f))
        assertTrue(MemoExclusion.subtract(s, listOf(TimeSpan(5f, 25f))).isEmpty())
        assertEquals(s, MemoExclusion.subtract(s, emptyList()))
    }
    // ── T264: Session-Memos ──────────────────────────────────────────

    private fun sm(start: Float?, end: Float?) = ch.etasystems.pirol.data.repository.SessionMemo(
        file = "memos/session_x.wav", startedAt = "2026-11-24T10:00:00+01:00", endedAt = "2026-11-24T10:00:10+01:00",
        startSec = start, endSec = end, durationSec = 10f
    )

    @Test
    fun sessionMemoSpans_margin_clip_and_invalid() {
        val r = MemoExclusion.sessionMemoSpans(listOf(sm(10f, 20f), sm(0.2f, 1f), sm(null, null), sm(100f, 110f), sm(5f, 400f)), 60f)
        // 0.2..1 -> 0..1.5, 10..20 -> 9.5..20.5; ohne Lage, ausserhalb und unplausibel lang (395 s) entfallen
        assertEquals(listOf(TimeSpan(0f, 1.5f), TimeSpan(9.5f, 20.5f)), r)
        // 3 min (Obergrenze) bleibt gueltig
        assertEquals(1, MemoExclusion.sessionMemoSpans(listOf(sm(10f, 190f)), 600f).size)
    }

    @Test
    fun session_memo_cut_from_human_sections_together_with_art_memo() {
        val sections = listOf(TimeSpan(0f, 60f))
        val session = MemoExclusion.sessionMemoSpans(listOf(sm(10f, 20f)), 60f)
        val art = MemoExclusion.toSessionSpans(listOf(WallSpan(140_000, 145_000)), 100_000, 60f)  // 40..45 s
        val r = MemoExclusion.subtract(sections, HumanWindows.union(art + session))
        assertEquals(listOf(TimeSpan(0f, 9.5f), TimeSpan(20.5f, 39.5f), TimeSpan(45.5f, 60f)), r)
    }

    @Test
    fun wallSpansByDetection_keyed() {
        val m = MemoExclusion.wallSpansByDetection(
            listOf(
                ev("a", VerificationEvent.ACTION_MEMO_RECORDED, 1000, 5000),
                ev("b", VerificationEvent.ACTION_MEMO_RECORDED)
            )
        )
        assertEquals(mapOf("a" to WallSpan(1000, 5000)), m)
    }
}
