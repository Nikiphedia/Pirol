package ch.etasystems.pirol.data.export

import ch.etasystems.pirol.data.repository.RecordingSegment
import ch.etasystems.pirol.ml.DetectionResult
import ch.etasystems.pirol.ui.analysis.DetectionOccurrences.MergeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/** T259: Raven-Zeilen pro Episode. */
class RavenEpisodesTest {

    private fun det(start: Float = 10f, window: Float = 3f, conf: Float = 0.9f, best: Float? = null, manual: Boolean = false) =
        DetectionResult(
            id = "a", scientificName = "Turdus merula", commonName = "Amsel", confidence = conf,
            timestampMs = 1_000_000L, chunkStartSec = start, chunkEndSec = start + window, sampleRate = 48000,
            bestStartSec = best, bestEndSec = best?.let { it + window }, manualEntry = manual
        )

    /** Merge mit Startzeit; Wanduhr folgt der Position (ms ab Anker bei 10 s). */
    private fun m(start: Float?, conf: Float? = 0.5f, tsSec: Float = start ?: 20f) =
        MergeInfo(timestampMs = 1_000_000L + ((tsSec - 10f) * 1000).toLong(), confidence = conf, startSec = start)

    @Test fun noMerges_oneRow() {
        assertEquals(listOf(RavenEpisodes.Row(10f, 13f, 0.9f, 1)), RavenEpisodes.rows(det(), emptyList()))
    }

    @Test fun touchingWindows_oneEpisode() {
        val r = RavenEpisodes.rows(det(conf = 0.9f), listOf(m(13f, 0.6f), m(16f, 0.7f)))
        assertEquals(1, r.size)
        assertEquals(10f, r[0].startSec, 1e-4f)
        assertEquals(19f, r[0].endSec, 1e-4f)
        assertEquals(3, r[0].windows)
        assertEquals(0.9f, r[0].confidence!!, 1e-4f)
    }

    @Test fun overlap50And67_oneEpisode() {
        val r50 = RavenEpisodes.rows(det(), listOf(m(11.5f), m(13f), m(14.5f)))
        assertEquals(1, r50.size)
        assertEquals(4, r50[0].windows)
        assertEquals(17.5f, r50[0].endSec, 1e-4f)
        val r67 = RavenEpisodes.rows(det(), listOf(m(11f), m(12f), m(13f), m(14f)))
        assertEquals(1, r67.size)
        assertEquals(5, r67[0].windows)
        assertEquals(17f, r67[0].endSec, 1e-4f)
    }

    @Test fun gapExactlyOneWindow_sameEpisode() {
        // Ende 13, naechster Beginn 16 -> Luecke 3 s = eine Fensterlaenge
        val r = RavenEpisodes.rows(det(), listOf(m(16f)))
        assertEquals(1, r.size)
        assertEquals(2, r[0].windows)
        assertEquals(19f, r[0].endSec, 1e-4f)
    }

    @Test fun gapLarger_newEpisode_maxConfidencePerEpisode() {
        // bestes Fenster spaeter (40 s): Anker-Score unbekannt, Episode 1 traegt nur 0.4 aus dem Merge
        val r = RavenEpisodes.rows(det(conf = 0.95f, best = 40f), listOf(m(13f, 0.4f), m(40f, 0.95f), m(43f, 0.6f)))
        assertEquals(2, r.size)
        assertEquals(10f, r[0].startSec, 1e-4f)
        assertEquals(16f, r[0].endSec, 1e-4f)
        assertEquals(0.4f, r[0].confidence!!, 1e-4f)
        assertEquals(2, r[0].windows)
        assertEquals(40f, r[1].startSec, 1e-4f)
        assertEquals(46f, r[1].endSec, 1e-4f)
        assertEquals(0.95f, r[1].confidence!!, 1e-4f)
        assertEquals(2, r[1].windows)
    }

    @Test fun gapSlightlyLarger_newEpisode() {
        val r = RavenEpisodes.rows(det(), listOf(m(16.1f)))
        assertEquals(2, r.size)
    }

    @Test fun anchorAloneWithUnknownScore_emptyConfidence() {
        val r = RavenEpisodes.rows(det(conf = 0.8f, best = 60f), listOf(m(60f, 0.8f)))
        assertEquals(2, r.size)
        assertNull(r[0].confidence)
        assertEquals(0.8f, r[1].confidence!!, 1e-4f)
    }

    @Test fun unorderedMergesAndDuplicates() {
        val r = RavenEpisodes.rows(det(), listOf(m(50f), m(16f), m(13f), m(53f), m(13f)))
        assertEquals(2, r.size)
        assertEquals(3, r[0].windows)
        assertEquals(19f, r[0].endSec, 1e-4f)
        assertEquals(50f, r[1].startSec, 1e-4f)
        assertEquals(2, r[1].windows)
    }

    @Test fun oldMergesWithoutStart_fallbackClusterRow() {
        val r = RavenEpisodes.rows(det(conf = 0.9f), listOf(m(null, null, tsSec = 30f), m(16f)))
        assertEquals(listOf(RavenEpisodes.Row(10f, 13f, 0.9f, 3)), r)
    }

    @Test fun manualEntry_fallback() {
        val r = RavenEpisodes.rows(det(start = 0f, window = 0f, conf = 1f, manual = true), emptyList())
        assertEquals(1, r.size)
        assertEquals(1, r[0].windows)
    }

    @Test fun exporter_rowsPerEpisode_segmentOfBegin() {
        val segments = listOf(RecordingSegment("recording.wav", 0f, 60f), RecordingSegment("recording-002.wav", 60f, 60f))
        val d = det(start = 55f, conf = 0.9f)
        val merges = mapOf("a" to listOf(m(58f, 0.5f), m(61f, 0.6f), m(100f, 0.7f)))
        val f = File.createTempFile("raven", ".txt")
        try {
            val n = RavenExporter.export(f, listOf(d), "2026-11-24T06:00:00+01:00", segments, merges = merges)
            assertEquals(2, n)
            val rows = f.readLines().map { it.split('\t') }
            val h = rows[0]
            assertEquals(listOf("Corrected Species", "Cluster", "Windows"), h.takeLast(3))
            fun cell(row: Int, name: String) = rows[row][h.indexOf(name)]
            // Episode 1 beginnt in Segment 1 und laeuft ueber die Grenze (55..64)
            assertEquals("recording.wav", cell(1, "Begin File"))
            assertEquals("55.000", cell(1, "Begin Time (s)"))
            assertEquals("64.000", cell(1, "End Time (s)"))
            assertEquals("3", cell(1, "Windows"))
            assertEquals("a", cell(1, "Cluster"))
            assertEquals("0.900", cell(1, "Confidence"))
            assertEquals("2026-11-24T06:00:55+01:00", cell(1, "Begin Date Time"))
            // Episode 2 in Segment 2, lokal 40..43
            assertEquals("recording-002.wav", cell(2, "Begin File"))
            assertEquals("40.000", cell(2, "Begin Time (s)"))
            assertEquals("43.000", cell(2, "End Time (s)"))
            assertEquals("0.700", cell(2, "Confidence"))
            assertEquals("1", cell(2, "Windows"))
            assertEquals("2", cell(2, "Selection"))
            assertEquals("a", cell(2, "Cluster"))
        } finally { f.delete() }
    }
}
