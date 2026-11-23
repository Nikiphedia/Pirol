package ch.etasystems.pirol.audio

import ch.etasystems.pirol.audio.export.DetectionExcerpt
import ch.etasystems.pirol.audio.export.ExcerptWav
import ch.etasystems.pirol.audio.export.FlacStreamInfo
import ch.etasystems.pirol.ml.DetectionResult
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.TimeZone

/** T116: Ausschnitts-Berechnung, Dateinamen, WAV-Writer und FLAC-STREAMINFO. */
class DetectionExcerptTest {

    private fun det(chunkStart: Float, lastOffsetSec: Float = 0f, window: Float = 3f) = DetectionResult(
        id = "x", scientificName = "Turdus merula", commonName = "Amsel", confidence = 0.9f,
        timestampMs = 1_000_000L, chunkStartSec = chunkStart, chunkEndSec = chunkStart + window,
        sampleRate = 48000, lastSeenAtMs = 1_000_000L + (lastOffsetSec * 1000).toLong()
    )

    @Test fun normal_detection_has_3s_pre_and_5s_post() {
        val r = DetectionExcerpt.compute(det(100f), fileDurationSec = 600f)
        assertEquals(97f, r.startSec, 0.001f)
        assertEquals(108f, r.endSec, 0.001f)  // 100 + 3 (Fenster) + 5
        assertEquals(100f, r.detectionStartSec, 0.001f)
    }

    @Test fun clustered_detection_extends_to_last_window() {
        val r = DetectionExcerpt.compute(det(100f, lastOffsetSec = 10f), fileDurationSec = 600f)
        assertEquals(97f, r.startSec, 0.001f)
        assertEquals(118f, r.endSec, 0.001f)  // 100 + 10 + 3 + 5
    }

    @Test fun clamps_at_file_start() {
        val r = DetectionExcerpt.compute(det(1f), fileDurationSec = 600f)
        assertEquals(0f, r.startSec, 0.001f)
        assertEquals(9f, r.endSec, 0.001f)
    }

    @Test fun clamps_at_file_end() {
        val r = DetectionExcerpt.compute(det(100f), fileDurationSec = 104f)
        assertEquals(97f, r.startSec, 0.001f)
        assertEquals(104f, r.endSec, 0.001f)
    }

    @Test fun long_detection_is_capped_keeping_start_with_preroll() {
        val r = DetectionExcerpt.compute(det(100f, lastOffsetSec = 120f), fileDurationSec = 600f, maxSec = 30f)
        assertEquals(97f, r.startSec, 0.001f)
        assertEquals(127f, r.endSec, 0.001f)
        assertEquals(30f, r.durationSec, 0.001f)
    }

    @Test fun cap_with_start_at_file_start() {
        val r = DetectionExcerpt.compute(det(0f, lastOffsetSec = 100f), fileDurationSec = 600f, maxSec = 20f)
        assertEquals(0f, r.startSec, 0.001f)
        assertEquals(20f, r.endSec, 0.001f)
    }

    @Test fun unknown_file_duration_does_not_clamp_end() {
        val r = DetectionExcerpt.compute(det(100f), fileDurationSec = 0f)
        assertEquals(108f, r.endSec, 0.001f)
    }

    @Test fun wav_header_and_byte_count() {
        val h = ExcerptWav.header(numSamples = 1000, sampleRate = 48000)
        assertEquals(44, h.size)
        assertEquals("RIFF", String(h, 0, 4))
        assertEquals("WAVE", String(h, 8, 4))
        val bb = java.nio.ByteBuffer.wrap(h).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        assertEquals(2036, bb.getInt(4))
        assertEquals(48000, bb.getInt(24))
        assertEquals(2000, bb.getInt(40))
    }

    @Test fun wav_write_and_read_range_roundtrip() {
        val f = File.createTempFile("t116", ".wav")
        try {
            val samples = ShortArray(48000 * 4) { (it % 1000).toShort() }
            ExcerptWav.write(f, ExcerptWav.Pcm(samples, 48000))
            assertEquals(44L + samples.size * 2L, f.length())
            assertEquals(4f, ExcerptWav.durationSec(f), 0.001f)
            val part = ExcerptWav.readRange(f, 1f, 2f)
            assertNotNull(part)
            assertEquals(48000, part!!.samples.size)
            assertArrayEquals(samples.copyOfRange(48000, 96000), part.samples)
            // ueber das Dateiende hinaus: gekappt
            assertEquals(48000, ExcerptWav.readRange(f, 3f, 10f)!!.samples.size)
        } finally {
            f.delete()
        }
    }

    @Test fun flac_streaminfo_layout() {
        val si = FlacStreamInfo.build(4096, 4096, 48000, 1, 16, 1_440_000L)
        assertEquals(34, si.size)
        // 48000 Hz = 0x0BB80 -> Bytes 10..11 = 0x0B 0xB8, Byte 12 hoch = 0x0
        assertEquals(0x0B, si[10].toInt() and 0xFF)
        assertEquals(0xB8, si[11].toInt() and 0xFF)
        assertEquals(0x0, (si[12].toInt() and 0xF0) shr 4)
        assertEquals(1_440_000L, FlacStreamInfo.totalSamples(si))
        val patched = FlacStreamInfo.withTotalSamples(si, 5_000_000_000L)
        assertEquals(5_000_000_000L, FlacStreamInfo.totalSamples(patched))
        val hdr = FlacStreamInfo.fileHeader(si)
        assertEquals("fLaC", String(hdr, 0, 4))
        assertEquals(0x80, hdr[4].toInt() and 0xFF)
        assertEquals(42, hdr.size)
    }

    @Test fun flac_config_extraction() {
        val si = FlacStreamInfo.build(4096, 4096, 48000, 1, 16, 0L)
        val full = FlacStreamInfo.fileHeader(si)
        assertArrayEquals(si, FlacStreamInfo.extractFromConfig(full))
        assertArrayEquals(si, FlacStreamInfo.extractFromConfig(si))
        assertTrue(FlacStreamInfo.extractFromConfig(ByteArray(5)) == null)
        assertTrue(FlacStreamInfo.isFrameStart(byteArrayOf(0xFF.toByte(), 0xF8.toByte(), 0)))
        assertFalse(FlacStreamInfo.isFrameStart(byteArrayOf(0x66, 0x4C)))
    }
}
