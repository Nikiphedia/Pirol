package ch.etasystems.pirol.data.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** T255: Segmentplan fuer Mehrfachimport (Dateigrenzen, Rotation, Wanduhr je Segment). */
class ImportSegmenterTest {

    private val zone = ZoneId.of("Europe/Zurich")
    private val t0 = ZonedDateTime.of(2024, 5, 1, 5, 0, 0, 0, zone)

    private class MemSink(val name: String, val closed: MutableList<String>) : ImportSegmenter.FrameSink {
        var frames = 0
        override fun append(samples: ShortArray) { frames += samples.size }
        override fun close() { closed += name }
    }

    @Test fun eachFileOwnSegment_gapsInWallClock_axisContiguous() {
        val sinks = mutableMapOf<String, MemSink>()
        val closed = mutableListOf<String>()
        val seg = ImportSegmenter(10, 1000) { n -> MemSink(n, closed).also { sinks[n] = it } }
        seg.startSource(t0)
        seg.write(ShortArray(60))             // 6 s
        seg.startSource(t0.plusMinutes(10))   // Luecke von 9:54
        seg.write(ShortArray(30)); seg.write(ShortArray(20))
        val list = seg.finish()
        assertEquals(listOf("recording.wav", "recording-002.wav"), list.map { it.fileName })
        assertEquals(0f, list[0].startSec)
        assertEquals(6f, list[1].startSec)     // Session-Achse ohne Luecke
        assertEquals(5f, list[1].durationSec)
        assertEquals("2024-05-01T05:00:00+02:00", list[0].startedAt)
        assertEquals("2024-05-01T05:10:00+02:00", list[1].startedAt)  // Wanduhr mit Luecke
        assertEquals(50, sinks.getValue("recording-002.wav").frames)
        assertEquals(listOf("recording.wav", "recording-002.wav"), closed)
        assertEquals(110L, seg.totalFrames)
    }

    @Test fun longFile_rotates_wallClockContinues() {
        val seg = ImportSegmenter(10, 25) { n -> MemSink(n, mutableListOf()) }
        seg.startSource(t0)
        seg.write(ShortArray(60))
        val list = seg.finish()
        assertEquals(3, list.size)
        assertEquals(listOf(2.5f, 2.5f, 1f), list.map { it.durationSec })
        assertEquals(listOf(0f, 2.5f, 5f), list.map { it.startSec })
        assertEquals("2024-05-01T05:00:02.5+02:00", list[1].startedAt)
        assertEquals("recording-003.wav", list[2].fileName)
    }

    @Test fun emptySource_noSegment() {
        val seg = ImportSegmenter(10, 100) { n -> MemSink(n, mutableListOf()) }
        seg.startSource(t0)
        seg.startSource(t0.plusHours(1))
        seg.write(ShortArray(5))
        val list = seg.finish()
        assertEquals(1, list.size)
        assertEquals("recording.wav", list[0].fileName)
        assertEquals("2024-05-01T06:00:00+02:00", list[0].startedAt)
    }

    @Test fun planner_groupsSortedAndMetadata() {
        data class F(val name: String, val t: ZonedDateTime)
        val files = listOf(F("b", t0.plusHours(1)), F("a", t0))
        assertEquals(listOf(listOf("a", "b")), ImportPlanner.groups(files, true) { it.t }.map { g -> g.map { it.name } })
        assertEquals(listOf(listOf("a"), listOf("b")), ImportPlanner.groups(files, false) { it.t }.map { g -> g.map { it.name } })
        assertEquals(44_100, ImportPlanner.targetRate(44_100))
        assertEquals(48_000, ImportPlanner.targetRate(384_000))
        assertEquals("2024-05-01T05-00-00_abc123", ImportPlanner.sessionId(t0, "abc123"))
        assertEquals("2024-05-01", ImportPlanner.dayDir(t0))

        val seg = ImportSegmenter(10, 1000) { n -> MemSink(n, mutableListOf()) }
        seg.startSource(t0); seg.write(ShortArray(60))
        seg.startSource(t0.plusMinutes(10)); seg.write(ShortArray(50))
        val segments = seg.finish()
        val meta = ImportPlanner.metadata("id", t0, 10, seg.totalFrames, segments, 47.0, 8.0, 0.5f, emptyList())
        assertEquals("2024-05-01T05:00:00+02:00", meta.startedAt)
        assertEquals("2024-05-01T05:10:05+02:00", meta.endedAt)  // Ende letztes Segment (Wanduhr)
        assertEquals(2, meta.recordingSegments!!.size)
        val single = ImportPlanner.metadata("id", t0, 10, 60, segments.take(1), null, null, 0.5f, emptyList())
        assertNull(single.recordingSegments)
        assertEquals("2024-05-01T05:00:06+02:00", single.endedAt)
    }
}
