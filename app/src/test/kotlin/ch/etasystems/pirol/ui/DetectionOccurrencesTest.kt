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

    private fun occ(start: Float, anchor: Boolean = false) = DetectionOccurrences.Occurrence(
        timestampMs = (start * 1000).toLong(), startSec = start, endSec = start + 3f, isAnchor = anchor
    )

    @Test
    fun episodes_singleWindow() {
        val e = DetectionOccurrences.episodes(listOf(occ(10f, true)))
        assertEquals(1, e.size)
        assertEquals(1, e[0].windowCount)
        assertTrue(e[0].containsAnchor)
    }

    @Test
    fun episodes_consecutiveWindowsMerge() {
        val e = DetectionOccurrences.episodes(listOf(occ(10f, true), occ(13f), occ(16f)))
        assertEquals(1, e.size)
        assertEquals(3, e[0].windowCount)
        assertEquals(10f, e[0].startSec, 0.001f)
        assertEquals(19f, e[0].endSec, 0.001f)
        assertEquals(9f, e[0].durationSec, 0.001f)
    }

    @Test
    fun episodes_gapExactlyAtToleranceStaysTogether_beyondSplits() {
        // Toleranz = Fensterlaenge 3 s + 1 s Jitter = 4 s Startabstand
        assertEquals(1, DetectionOccurrences.episodes(listOf(occ(10f, true), occ(14f))).size)
        val split = DetectionOccurrences.episodes(listOf(occ(10f, true), occ(14.1f)))
        assertEquals(2, split.size)
        assertFalse(split[1].containsAnchor)
    }

    @Test
    fun episodes_jitterOnOverlapHopIsTolerated() {
        val e = DetectionOccurrences.episodes(listOf(occ(10f, true), occ(11.4f), occ(12.9f), occ(14.6f)))
        assertEquals(1, e.size)
        assertEquals(4, e[0].windowCount)
    }

    @Test
    fun episodes_unsortedInputAndEmpty() {
        val e = DetectionOccurrences.episodes(listOf(occ(40f), occ(10f, true), occ(13f)))
        assertEquals(listOf(10f, 40f), e.map { it.startSec })
        assertEquals(listOf(2, 1), e.map { it.windowCount })
        assertTrue(DetectionOccurrences.episodes(emptyList()).isEmpty())
    }

    @Test
    fun implausibleWindow_usesFallback() {
        val d = det().copy(chunkEndSec = 0f)
        assertEquals(63f, DetectionOccurrences.of(d, emptyList())[0].endSec, 0.001f)
    }
}
