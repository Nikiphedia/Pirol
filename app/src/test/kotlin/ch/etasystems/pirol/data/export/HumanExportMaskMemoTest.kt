package ch.etasystems.pirol.data.export

import ch.etasystems.pirol.audio.export.FrameSpan
import ch.etasystems.pirol.audio.export.SegmentedRecording
import ch.etasystems.pirol.data.repository.ExportEntry
import ch.etasystems.pirol.data.repository.SessionExportRules
import ch.etasystems.pirol.ml.HumanLabels
import ch.etasystems.pirol.ml.ModelProfile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random

/** T263: Memo-Dateien bleiben im Export bitgenau; Entscheidungslogik fuer Modelle ohne Mensch-Klasse. */
class HumanExportMaskMemoTest {

    private val sr = 48000

    /** WAV 16 bit mono mit Rauschen (feste Startzahl, reproduzierbar) */
    private fun wav(seconds: Int, seed: Long): ByteArray {
        val n = sr * seconds
        val bb = ByteBuffer.allocate(44 + n * 2).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()).putInt(36 + n * 2).put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(sr).putInt(sr * 2).putShort(2).putShort(16)
        bb.put("data".toByteArray()).putInt(n * 2)
        val r = Random(seed)
        repeat(n) { bb.putShort(((r.nextInt(20001)) - 10000).toShort()) }
        return bb.array()
    }

    private fun readAll(e: ExportEntry): ByteArray = e.open().use { it.readBytes() }

    @Test
    fun memo_entries_stay_bit_exact_while_recording_is_masked() {
        val dir = File.createTempFile("t263", "").apply { delete(); mkdirs() }
        try {
            val recBytes = wav(4, 1)
            val memoBytes = wav(2, 2)
            val rec = File(dir, "recording.wav").apply { writeBytes(recBytes) }
            val recording = SegmentedRecording.fromFiles(listOf(rec), sr)
            // Mensch-Abschnitt ueber 1 s .. 3 s der Aufnahme
            val plan = HumanExportMask.Plan(recording, listOf(FrameSpan(sr.toLong(), 3L * sr)))
            val entries = listOf(
                ExportEntry("recording.wav") { ByteArrayInputStream(recBytes) },
                ExportEntry("memos/det_1.wav") { ByteArrayInputStream(memoBytes) },
                ExportEntry("session.json") { ByteArrayInputStream("{}".toByteArray()) }
            )
            val out = HumanExportMask.wrapEntries(entries, plan)
            assertEquals(entries.map { it.path }, out.map { it.path })
            // Memo: dasselbe Objekt, Bytes unveraendert
            assertSame(entries[1], out[1])
            assertArrayEquals(memoBytes, readAll(out[1]))
            assertSame(entries[2], out[2])
            // Aufnahme: bearbeitet (gleiche Laenge, Inhalt im Abschnitt anders, ausserhalb gleich)
            val masked = readAll(out[0])
            assertEquals(recBytes.size, masked.size)
            assertFalse(recBytes.contentEquals(masked))
            val from = 44 + 1 * sr * 2 + 2000 * 2
            val to = 44 + 3 * sr * 2 - 2000 * 2
            assertFalse(recBytes.copyOfRange(from, to).contentEquals(masked.copyOfRange(from, to)))
            assertArrayEquals(recBytes.copyOfRange(0, 44 + sr * 2 / 2), masked.copyOfRange(0, 44 + sr * 2 / 2))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun memo_files_are_part_of_export_and_not_excluded() {
        assertFalse(SessionExportRules.isExcluded("det_1.wav"))
        val dir = File.createTempFile("t263e", "").apply { delete(); mkdirs() }
        try {
            File(dir, "memos").mkdirs()
            File(dir, "memos/det_1.wav").writeBytes(wav(1, 3))
            File(dir, "recording.wav").writeBytes(wav(1, 4))
            // T303: Art-Memo mit Bezug (voiceMemoFile der Detektion)
            File(dir, "detections.jsonl").writeText(ch.etasystems.pirol.data.repository.SessionJson.compact.encodeToString(
                ch.etasystems.pirol.ml.DetectionResult.serializer(),
                ch.etasystems.pirol.ml.DetectionResult(id = "1", scientificName = "Turdus merula", commonName = "Amsel",
                    confidence = 0.9f, timestampMs = 1L, chunkStartSec = 0f, chunkEndSec = 3f, sampleRate = 48000,
                    voiceMemoFile = "memos/det_1.wav")) + "\n")
            val paths = SessionExportRules.entriesOf(dir).map { it.path }
            assertTrue("memos/det_1.wav" in paths)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun without_plan_or_without_sections_entries_are_unchanged() {
        val e = listOf(ExportEntry("recording.wav") { ByteArrayInputStream(ByteArray(4)) })
        assertSame(e, HumanExportMask.wrapEntries(e, null))
    }

    // ── Entscheidungslogik Modell ohne Mensch-Klasse ──

    @Test
    fun model_with_human_class_scores_model_without_returns_null() {
        val birdnet = listOf("Turdus_merula", "Homo_sapiens", "Parus_major")
        val nighthawk = listOf("Turdus_merula", "Parus_major", "Strix_aluco")
        assertEquals(1, HumanLabels.indicesOf(birdnet).size)
        assertNotNull(HumanLabels.score(floatArrayOf(0.1f, 0.4f, 0.2f), HumanLabels.indicesOf(birdnet)))
        // kein Mensch-Score -> Aufrufer wirft Unavailable -> Dialog
        assertNull(HumanLabels.score(floatArrayOf(0.1f, 0.4f, 0.2f), HumanLabels.indicesOf(nighthawk)))
    }

    @Test
    fun birdnet_option_installed_or_not_and_recording() {
        val finalFile = ModelProfile.V3_FINAL_FP16.fileName
        val preview = ModelProfile.PREVIEW31_FP32.fileName
        // nichts installiert
        assertNull(HumanExportMask.pickBirdNetFile({ false }, false))
        // 3.0 final bevorzugt, auch wenn Preview da ist
        assertEquals(finalFile, HumanExportMask.pickBirdNetFile({ it == finalFile || it == preview }, false))
        // nur Preview
        assertEquals(preview, HumanExportMask.pickBirdNetFile({ it == preview }, false))
        // laufende Aufnahme: nie (kein zweites grosses Modell neben der Live-Inferenz)
        assertNull(HumanExportMask.pickBirdNetFile({ true }, true))
    }

    @Test
    fun choice_roundtrip_for_work_data() {
        val c = HumanExportMask.Choice
        assertEquals(HumanExportMask.Choice.Active, c.decode(null))
        assertEquals(HumanExportMask.Choice.Active, c.decode(""))
        assertNull(c.encode(HumanExportMask.Choice.Active))
        assertEquals(HumanExportMask.Choice.Skip, c.decode(c.encode(HumanExportMask.Choice.Skip)))
        val b = HumanExportMask.Choice.BirdNet("birdnet_v3_final_fp16.onnx")
        assertEquals(b, c.decode(c.encode(b)))
        assertEquals(HumanExportMask.Choice.Active, c.decode("birdnet:"))  // unvollstaendig = Standard (nie unmaskiert)
    }
}
