package ch.etasystems.pirol.ui

import ch.etasystems.pirol.ml.DetectionResult
import ch.etasystems.pirol.ui.analysis.DetectionOccurrences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T167: Gruppierung der Einzelvorkommen einer geclusterten Detektion. */
class DetectionOccurrencesTest {

    private fun det(start: Float = 60f, ts: Long = 1_000_000L) = DetectionResult(
        id = "a", scientificName = "Parus major", commonName = "Kohlmeise", confidence = 0.8f,
        timestampMs = ts, chunkStartSec = start, chunkEndSec = start + 3f, sampleRate = 48000
    )

    @Test
    fun parse_extractsLastSeenAt() {
        assertEquals(
            123456L,
            DetectionOccurrences.parseMergeTimestamp("merged:x|count:3|lastSeenAt:123456")
        )
    }

    @Test
    fun parse_returnsNullForGarbage() {
        assertNull(DetectionOccurrences.parseMergeTimestamp(null))
        assertNull(DetectionOccurrences.parseMergeTimestamp("merged:x|count:3"))
        assertNull(DetectionOccurrences.parseMergeTimestamp("merged:x|lastSeenAt:abc"))
    }

    @Test
    fun withoutMerges_onlyAnchor() {
        val occ = DetectionOccurrences.of(det(), emptyList())
        assertEquals(1, occ.size)
        assertTrue(occ[0].isAnchor)
        assertEquals(60f, occ[0].startSec, 0.001f)
        assertEquals(63f, occ[0].endSec, 0.001f)
    }

    @Test
    fun merges_mapToSessionOffsetsViaWallClockDelta() {
        val occ = DetectionOccurrences.of(det(), listOf(1_009_000L, 1_003_000L))
        assertEquals(3, occ.size)
        assertEquals(listOf(60f, 63f, 69f), occ.map { it.startSec })
        assertFalse(occ[1].isAnchor)
        assertEquals(72f, occ[2].endSec, 0.001f)
    }

    @Test
    fun duplicatesAndPreAnchorTimestampsAreDropped() {
        val occ = DetectionOccurrences.of(det(), listOf(999_000L, 1_000_100L, 1_003_000L, 1_003_200L))
        assertEquals(listOf(60f, 63f), occ.map { it.startSec })
    }

    @Test
    fun implausibleWindow_usesFallback() {
        val d = det().copy(chunkEndSec = 0f)
        assertEquals(63f, DetectionOccurrences.of(d, emptyList())[0].endSec, 0.001f)
    }
}
