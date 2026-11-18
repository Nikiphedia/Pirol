package ch.etasystems.pirol.audio

import ch.etasystems.pirol.audio.export.SegmentedRecording
import ch.etasystems.pirol.data.repository.StreamingWavWriter
import ch.etasystems.pirol.ml.DetectionResult
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

/**
 * T130: Session-Zeit → (Segment, Offset) und Lesen ueber Segmentgrenzen.
 * Testdaten: Sample-Wert = globaler Sample-Index (mod Short), damit Luecken/Duplikate auffallen.
 */
class SegmentedRecordingTest {

    private lateinit var dir: File
    private val sr = 1000  // kleine Rate → kleine Testdateien

    @Before fun setUp() { dir = Files.createTempDirectory("t130seg").toFile() }
    @After fun tearDown() { dir.deleteRecursively() }

    private fun samples(from: Int, count: Int) = ShortArray(count) { (from + it).toShort() }

    private fun writeWav(name: String, data: ShortArray): File {
        val f = File(dir, name)
        StreamingWavWriter.forFile(f, sr).apply { open(); append(data); close() }
        return f
    }

    /** 3 Segmente: 2.0 s, 1.5 s, 1.0 s */
    private fun threeSegments(): SegmentedRecording {
        writeWav("recording.wav", samples(0, 2000))
        writeWav("recording-002.wav", samples(2000, 1500))
        writeWav("recording-003.wav", samples(3500, 1000))
        return SegmentedRecording.forSession(dir)!!
    }

    @Test fun segment_files_sorted_by_index_not_lexically() {
        for (i in listOf(0, 9, 1)) {
            val name = if (i == 0) "recording.wav" else "recording-%03d.wav".format(i + 1)
            writeWav(name, samples(0, 10))
        }
        File(dir, "recording.selections.txt").writeText("x")
        val names = SegmentedRecording.segmentFiles(dir).map { it.name }
        assertEquals(listOf("recording.wav", "recording-002.wav", "recording-010.wav"), names)
        assertEquals(0, SegmentedRecording.segmentIndex("recording.wav"))
        assertEquals(1, SegmentedRecording.segmentIndex("recording-002.wav"))
        assertNull(SegmentedRecording.segmentIndex("memo.wav"))
    }

    @Test fun locate_maps_session_time_to_segment_and_offset() {
        val rec = threeSegments()
        assertEquals(4.5f, rec.durationSec, 1e-4f)
        rec.locate(0.5f)!!.let { assertEquals(0, it.partIndex); assertEquals(0.5f, it.offsetSec, 1e-4f) }
        rec.locate(2.0f)!!.let { assertEquals(1, it.partIndex); assertEquals(0f, it.offsetSec, 1e-4f) }
        rec.locate(3.6f)!!.let {
            assertEquals(2, it.partIndex); assertEquals("recording-003.wav", it.file.name)
            assertEquals(0.1f, it.offsetSec, 1e-4f)
        }
        // Ausserhalb → Rand
        rec.locate(99f)!!.let { assertEquals(2, it.partIndex); assertEquals(1.0f, it.offsetSec, 1e-4f) }
    }

    @Test fun read_range_spanning_two_boundaries_is_contiguous() {
        val rec = threeSegments()
        val pcm = rec.readRange(1.5f, 4.0f)!!
        assertEquals(sr, pcm.sampleRate)
        assertArrayEquals(samples(1500, 2500), pcm.samples)
    }

    @Test fun read_range_inside_one_segment_and_clamped_at_end() {
        val rec = threeSegments()
        assertArrayEquals(samples(2100, 200), rec.readRange(2.1f, 2.3f)!!.samples)
        assertArrayEquals(samples(4000, 500), rec.readRange(4.0f, 10f)!!.samples)
        assertNull(rec.readRange(5f, 6f))
    }

    @Test fun virtual_wav_stream_has_header_and_skips_across_segments() {
        val rec = threeSegments()
        val bytes = rec.openWavStream().use { s ->
            val header = ByteArray(44)
            assertEquals(44, s.read(header))
            val hb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(sr, hb.getInt(24))
            assertEquals(4500 * 2, hb.getInt(40))
            // Bis Sample 1990 springen und 30 Samples ueber die erste Grenze lesen
            assertEquals(1990L * 2, s.skip(1990L * 2))
            val buf = ByteArray(60)
            var read = 0
            while (read < buf.size) { val n = s.read(buf, read, buf.size - read); if (n < 0) break; read += n }
            buf
        }
        val got = ShortArray(30)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(got)
        assertArrayEquals(samples(1990, 30), got)
        assertEquals(44L + 4500 * 2, rec.virtualWavLength)
    }

    @Test fun single_file_session_unchanged() {
        val f = writeWav("recording.wav", samples(0, 3000))
        val rec = SegmentedRecording.forRecordingFile(f)
        assertEquals(1, rec.parts.size)
        assertArrayEquals(samples(1000, 500), rec.readRange(1f, 1.5f)!!.samples)
    }

    @Test fun excerpt_across_boundary_uses_session_axis() {
        val rec = threeSegments()
        // Detektion bei 2.5 s (Segment 2) → Ausschnitt 3 s davor ab 0, Ende begrenzt auf Session-Ende
        val d = det(chunkStart = 2.5f)
        val r = rec.excerptRange(d, maxSec = 30f)
        assertEquals(0f, r.startSec, 1e-4f)
        assertEquals(4.5f, r.endSec, 1e-4f)
        assertEquals(4500, rec.readRange(r.startSec, r.endSec)!!.samples.size)
    }

    @Test fun legacy_local_offsets_resolved_by_wall_clock() {
        // Segment-Starts 0, 600, 1200 s; lokale Offsets 10 s; Wanduhr 615 s nach Start (+ Preroll)
        val starts = listOf(0f, 600f, 1200f)
        assertEquals(600f, SegmentedRecording.legacySegmentStartSec(10f, 615f, starts), 0f)
        assertEquals(0f, SegmentedRecording.legacySegmentStartSec(10f, 5f, starts), 0f)
        assertEquals(1200f, SegmentedRecording.legacySegmentStartSec(10f, 1190f, starts), 0f)

        writeWav("recording.wav", samples(0, 2000))
        writeWav("recording-002.wav", samples(2000, 2000))
        val legacy = SegmentedRecording.forSession(dir, legacyLocalOffsets = true, startedAtEpochMs = 1_000_000L)!!
        // Wanduhr 2.6 s nach Start, lokaler Offset 0.5 s → Segment 2 → Session 2.5 s
        val d = det(chunkStart = 0.5f, timestampMs = 1_002_600L)
        assertEquals(2.5f, legacy.sessionRelative(d).chunkStartSec, 1e-4f)
        // Neue Session (Flag aus): unveraendert
        val current = SegmentedRecording.forSession(dir)!!
        assertEquals(0.5f, current.sessionRelative(d).chunkStartSec, 0f)
    }

    @Test fun empty_dir_has_no_recording() {
        assertNull(SegmentedRecording.forSession(dir))
        assertNotNull(dir)
    }

    private fun det(chunkStart: Float, timestampMs: Long = 1_000_000L) = DetectionResult(
        id = "x", scientificName = "Turdus merula", commonName = "Amsel", confidence = 0.9f,
        timestampMs = timestampMs, chunkStartSec = chunkStart, chunkEndSec = chunkStart + 0.5f,
        sampleRate = sr, lastSeenAtMs = timestampMs
    )
}
