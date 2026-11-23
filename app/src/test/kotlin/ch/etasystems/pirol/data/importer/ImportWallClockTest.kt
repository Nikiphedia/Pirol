package ch.etasystems.pirol.data.importer

import ch.etasystems.pirol.data.export.RavenExporter
import ch.etasystems.pirol.data.repository.RecordingSegment
import ch.etasystems.pirol.ml.DetectionResult
import ch.etasystems.pirol.rescan.RescanPlan
import ch.etasystems.pirol.util.parseInstantCompat
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** T255: Wanduhr importierter Segmente mit Luecken (Rescan-Zeitstempel, Raven Begin Date Time). */
class ImportWallClockTest {

    private val segments = listOf(
        RecordingSegment("recording.wav", 0f, 60f, "2024-05-01T05:00:00+02:00"),
        RecordingSegment("recording-002.wav", 60f, 60f, "2024-05-01T05:10:00+02:00")
    )
    private fun ms(iso: String) = parseInstantCompat(iso)!!.toEpochMilli()

    @Test fun rescan_wallClockPerSegment() {
        val epoch0 = ms("2024-05-01T05:00:00+02:00")
        val at = RescanPlan.wallClockAt(segments, 100, epoch0)
        assertEquals(ms("2024-05-01T05:00:30+02:00"), at(3_000))      // 30 s in Segment 1
        assertEquals(ms("2024-05-01T05:10:00+02:00"), at(6_000))      // Segment 2 beginnt nach der Luecke
        assertEquals(ms("2024-05-01T05:10:15+02:00"), at(7_500))
    }

    @Test fun rescan_withoutWallClock_linear() {
        val at = RescanPlan.wallClockAt(listOf(RecordingSegment("recording.wav", 0f, 60f)), 100, 1_000L)
        assertEquals(1_000L + 75_000L, at(7_500))
        assertEquals(1_000L + 10_000L, RescanPlan.wallClockAt(null, 100, 1_000L)(1_000))
    }

    @Test fun raven_beginDateTime_fromSegment() {
        val d = DetectionResult(
            id = "a", scientificName = "Turdus merula", commonName = "Amsel", confidence = 0.8f,
            timestampMs = 0L, chunkStartSec = 75f, chunkEndSec = 78f, sampleRate = 48000
        )
        val f = File.createTempFile("raven", ".txt")
        try {
            RavenExporter.export(f, listOf(d), "2024-05-01T05:00:00+02:00", segments)
            val rows = f.readLines().map { it.split('\t') }
            val col = rows[0].indexOf("Begin Date Time")
            assertEquals("2024-05-01T05:10:15+02:00", rows[1][col])
            assertEquals("recording-002.wav", rows[1][rows[0].indexOf("Begin File")])
        } finally { f.delete() }
    }
}
