package ch.etasystems.pirol.ui

import ch.etasystems.pirol.data.repository.RecordingSegment
import ch.etasystems.pirol.ui.analysis.ManualEntryTime
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.OffsetDateTime

/** T274: manueller Eintrag in importierter Session mit Luecke zwischen zwei Segmenten. */
class ManualEntryTimeGapTest {
    private val t0 = "2026-05-01T05:00:00+02:00"
    private val t1 = "2026-05-01T06:00:00+02:00"  // Segment 2 beginnt 1 h spaeter (Segment 1 dauert 60 s)
    private val segs = listOf(
        RecordingSegment("recording.wav", 0f, 60f, t0),
        RecordingSegment("recording-002.wav", 60f, 60f, t1)
    )
    private fun ms(s: String) = OffsetDateTime.parse(s).toInstant().toEpochMilli()

    @Test
    fun secondSegment_usesWallClockOfSegment() {
        val t = ManualEntryTime.compute(90f, 120f, ms(t0), 0L, segs)
        assertEquals(ms(t1) + 30_000L, t.timestampMs)
        assertEquals(80f, t.chunkStartSec, 0.001f)
        assertEquals(100f, t.chunkEndSec, 0.001f)
    }

    @Test
    fun nearBoundary_windowStaysInsideSegment() {
        val t = ManualEntryTime.compute(65f, 120f, ms(t0), 0L, segs)
        assertEquals(60f, t.chunkStartSec, 0.001f)
        assertEquals(75f, t.chunkEndSec, 0.001f)
        assertEquals(ms(t1) + 5_000L, t.timestampMs)
        val a = ManualEntryTime.compute(55f, 120f, ms(t0), 0L, segs)
        assertEquals(45f, a.chunkStartSec, 0.001f)
        assertEquals(60f, a.chunkEndSec, 0.001f)
        assertEquals(ms(t0) + 55_000L, a.timestampMs)
    }

    @Test
    fun segmentsWithoutWallClock_stayLinear() {
        val live = listOf(RecordingSegment("recording.wav", 0f, 60f), RecordingSegment("recording-002.wav", 60f, 60f))
        val t = ManualEntryTime.compute(90f, 120f, ms(t0), 0L, live)
        assertEquals(ms(t0) + 90_000L, t.timestampMs)
        assertEquals(80f, t.chunkStartSec, 0.001f)
    }
}
