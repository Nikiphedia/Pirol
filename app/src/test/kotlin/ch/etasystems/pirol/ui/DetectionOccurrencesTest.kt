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

    private fun mi(vararg ts: Long) = ts.map { DetectionOccurrences.MergeInfo(it) }

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
        val occ = DetectionOccurrences.of(det(), mi(1_009_000L, 1_003_000L))
        assertEquals(3, occ.size)
        assertEquals(listOf(60f, 63f, 69f), occ.map { it.startSec })
        assertFalse(occ[1].isAnchor)
        assertEquals(72f, occ[2].endSec, 0.001f)
    }

    @Test
    fun duplicatesAndPreAnchorTimestampsAreDropped() {
        val occ = DetectionOccurrences.of(det(), mi(999_000L, 1_000_100L, 1_003_000L, 1_003_200L))
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

    @Test
    fun parseMergeInfo_newAndLegacyFormat() {
        val n = DetectionOccurrences.parseMergeInfo("merged:x|count:3|lastSeenAt:123456|conf:0.8125|start:72.5")!!
        assertEquals(123456L, n.timestampMs)
        assertEquals(0.8125f, n.confidence!!, 0.0001f)
        assertEquals(72.5f, n.startSec!!, 0.0001f)
        val old = DetectionOccurrences.parseMergeInfo("merged:x|count:3|lastSeenAt:123456")!!
        assertNull(old.confidence)
        assertNull(old.startSec)
        assertNull(DetectionOccurrences.parseMergeInfo("merged:x|conf:0.5|start:3"))
        assertNull(DetectionOccurrences.parseMergeInfo("merged:x|lastSeenAt:1|conf:abc|start:NaN")!!.confidence)
        assertNull(DetectionOccurrences.parseMergeInfo("merged:x|lastSeenAt:1|conf:1.7|start:-2")!!.startSec)
    }

    @Test
    fun exactStart_winsOverWallClock() {
        val m = DetectionOccurrences.MergeInfo(1_003_000L, confidence = 0.5f, startSec = 64.2f)
        val occ = DetectionOccurrences.of(det().copy(confidence = 0.9f), listOf(m))
        assertEquals(64.2f, occ[1].startSec, 0.001f)
        assertEquals(67.2f, occ[1].endSec, 0.001f)
        assertEquals(0.5f, occ[1].confidence!!, 0.001f)
        // Anker: 0.9 > absorbiert 0.5 -> Anker-Konfidenz bekannt
        assertEquals(0.9f, occ[0].confidence!!, 0.001f)
    }

    @Test
    fun anchorConfidence_unknownWhenAbsorbedReachesMax() {
        val m = DetectionOccurrences.MergeInfo(1_003_000L, confidence = 0.8f, startSec = 63f)
        val occ = DetectionOccurrences.of(det(), listOf(m))  // det.confidence = 0.8
        assertNull(occ[0].confidence)
        assertEquals(0.8f, occ[1].confidence!!, 0.001f)
    }

    @Test
    fun legacy_anchorKeepsMaxAndAbsorbedHaveNone() {
        val occ = DetectionOccurrences.of(det(), mi(1_003_000L))
        assertEquals(0.8f, occ[0].confidence!!, 0.001f)
        assertNull(occ[1].confidence)
    }

    @Test
    fun episode_maxConfidenceOverWindows() {
        fun o(s: Float, c: Float?) = DetectionOccurrences.Occurrence((s * 1000).toLong(), s, s + 3f, false, c)
        val e = DetectionOccurrences.episodes(listOf(o(10f, 0.4f), o(13f, 0.9f), o(16f, null), o(40f, null)))
        assertEquals(0.9f, e[0].maxConfidence!!, 0.001f)
        assertNull(e[1].maxConfidence)
    }
}
