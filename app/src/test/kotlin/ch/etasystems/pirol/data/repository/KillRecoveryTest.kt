package ch.etasystems.pirol.data.repository

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.time.ZonedDateTime

/**
 * T130: Prozess-Kill waehrend der Aufnahme — StreamingWavWriter wird NIE geschlossen.
 */
class KillRecoveryTest {

    private lateinit var dir: File
    private val sr = 48000

    @Before fun setUp() { dir = Files.createTempDirectory("t130kill").toFile() }
    @After fun tearDown() { dir.deleteRecursively() }

    private fun headerSizes(f: File): Pair<Long, Long> = RandomAccessFile(f, "r").use { raf ->
        val h = ByteArray(44); raf.readFully(h)
        val bb = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
        (bb.getInt(4).toLong() and 0xFFFFFFFFL) to (bb.getInt(40).toLong() and 0xFFFFFFFFL)
    }

    @Test fun header_sizes_follow_every_5s_without_close() {
        val f = File(dir, "recording.wav")
        val w = StreamingWavWriter.forFile(f, sr).also { it.open() }
        val hop = ShortArray(sr * 3 / 2)  // 1.5-s-Hops (Overlap 50 %)
        repeat(4) { w.append(hop) }       // 6 s → ein Sync bei >= 5 s
        // Kill: kein close(). Alle Samples sind trotzdem in der Datei.
        assertEquals(44L + 4L * hop.size * 2, f.length())
        val (riff, data) = headerSizes(f)
        assertEquals(4L * hop.size * 2, data)
        assertEquals(36L + data, riff)
        assertFalse(WavWriter.headerSizesStale(f))

        // Weitere 1.5 s (unter dem Intervall) → Header veraltet, Reparatur setzt Dateilaenge
        w.append(hop)
        assertTrue(WavWriter.headerSizesStale(f))
        StreamingWavWriter.finalizeHeader(f)
        assertEquals(5L * hop.size * 2, headerSizes(f).second)
        assertFalse(WavWriter.headerSizesStale(f))
        w.close()  // nur Aufraeumen (Windows: offene Datei nicht loeschbar)
    }

    @Test fun max_header_lag_is_below_sync_interval() {
        val f = File(dir, "recording.wav")
        val w = StreamingWavWriter.forFile(f, sr).also { it.open() }
        val hop = ShortArray(sr)  // 1-s-Hops (Overlap 67 %)
        var maxLagBytes = 0L
        repeat(23) {
            w.append(hop)
            maxLagBytes = maxOf(maxLagBytes, (f.length() - 44L) - headerSizes(f).second)
        }
        assertTrue("Header-Rueckstand $maxLagBytes", maxLagBytes < sr * 2L * StreamingWavWriter.SYNC_INTERVAL_SEC)
        w.close()
    }

    @Test fun recover_metadata_rebuilds_segments_duration_and_end() {
        val rate = 1000
        val seg1 = File(dir, "recording.wav")
        val seg2 = File(dir, "recording-002.wav")
        StreamingWavWriter.forFile(seg1, rate).apply { open(); append(ShortArray(600_000)); close() }
        val killed = StreamingWavWriter.forFile(seg2, rate).also { it.open(); it.append(ShortArray(2_500)) }  // Kill
        val started = ZonedDateTime.parse("2026-04-20T14:30:00+02:00")
        // lastModified vor startedAt (z.B. kopiert) → endedAt = startedAt + Dauer
        seg1.setLastModified(1000L); seg2.setLastModified(1000L)

        val meta = SessionMetadata(
            sessionId = "s", startedAt = started.toString(), sampleRate = rate,
            confidenceThreshold = 0.5f, sessionRelativeOffsets = true
        )
        val rec = SessionManager.recoverMetadata(meta, listOf(seg1, seg2), detectionLineCount = 7)
        assertEquals(602_500L, rec.totalRecordedSamples)
        assertEquals(7, rec.totalDetections)
        val segs = rec.recordingSegments
        assertNotNull(segs)
        assertEquals(listOf("recording.wav", "recording-002.wav"), segs!!.map { it.fileName })
        assertEquals(600f, segs[1].startSec, 1e-3f)
        assertEquals(2.5f, segs[1].durationSec, 1e-3f)
        assertEquals(started.plusNanos(602_500_000_000L).toInstant(), ZonedDateTime.parse(rec.endedAt).toInstant())
        assertTrue(rec.endedAt!!.endsWith("+02:00"))
        killed.close()
    }

    @Test fun recover_metadata_single_file_keeps_no_segment_list() {
        val f = File(dir, "recording.wav")
        val w = StreamingWavWriter.forFile(f, sr).also { it.open(); it.append(ShortArray(sr)) }
        val meta = SessionMetadata(sessionId = "s", startedAt = "2020-01-01T00:00:00+01:00", sampleRate = sr, confidenceThreshold = 0.5f)
        val rec = SessionManager.recoverMetadata(meta, listOf(f), detectionLineCount = null)
        assertNull(rec.recordingSegments)
        assertEquals(sr.toLong(), rec.totalRecordedSamples)
        assertNotNull(rec.endedAt)  // aus lastModified (nach startedAt)
        w.close()
    }

    @Test fun session_offset_spans_segments() {
        assertEquals(3600.5f, SessionManager.sessionOffsetSec(3600L * sr, sr / 2L, sr), 1e-3f)
        assertEquals("recording.wav", SessionManager.segmentFileName(0))
        assertEquals("recording-002.wav", SessionManager.segmentFileName(1))
    }
}
