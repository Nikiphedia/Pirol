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
}
