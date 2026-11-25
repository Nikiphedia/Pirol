package ch.etasystems.pirol.data.repository

import ch.etasystems.pirol.ml.DetectionResult
import kotlinx.serialization.encodeToString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.time.ZoneId

/**
 * T303 (Befund 1): Memo-WAVs gehen nur mit Bezug in Export/ZIP/Upload (Session-Memo in memos.jsonl,
 * Art-Memo per voiceMemoFile der aktuellen Fassung, Original und Varianten), nie in der Undo-Frist.
 */
class MemoExportReferenceTest {

    private lateinit var dir: File
    private val zone = ZoneId.of("Europe/Zurich")

    @Before fun setUp() { dir = Files.createTempDirectory("t303m").toFile() }
    @After fun tearDown() { dir.deleteRecursively() }

    private fun det(id: String, memo: String?) = SessionJson.compact.encodeToString(DetectionResult(
        id = id, scientificName = "Turdus merula", commonName = "Amsel", confidence = 0.9f,
        timestampMs = 1L, chunkStartSec = 0f, chunkEndSec = 3f, sampleRate = 48000, voiceMemoFile = memo
    ))

    private fun memoLine(file: String) = SessionMemos.encode(SessionMemos.create(file.removePrefix("memos/"), 1_000L, 6_000L, 2f, zone))

    private fun wav(name: String) = File(dir, "memos/$name").apply { parentFile?.mkdirs(); writeBytes(ByteArray(44)) }

    private fun paths() = SessionExportRules.entriesOf(dir).map { it.path }.toSet()

    @Test fun session_and_art_memos_with_reference_are_exported_orphans_not() {
        wav("session_20260420-143500.wav")
        wav("det_a.wav")
        wav("session_20260420-150000.wav")  // verwaist: Eintrag geloescht, WAV blieb liegen
        wav("det_b.wav")                    // verwaist: Art-Memo geloescht
        File(dir, "memos.jsonl").writeText(memoLine("memos/session_20260420-143500.wav"))
        File(dir, "detections.jsonl").writeText(det("a", "memos/det_a.wav") + "\n" + det("b", "memos/det_b.wav") + "\n" + det("b", null) + "\n")
        val p = paths()
        assertTrue("memos/session_20260420-143500.wav" in p)
        assertTrue("memos/det_a.wav" in p)
        assertTrue("memos/session_20260420-150000.wav" !in p)
        assertTrue("memos/det_b.wav" !in p)  // letzte Fassung von b hat kein Memo mehr
        assertTrue("detections.jsonl" in p && "memos.jsonl" in p)
    }

    @Test fun variant_detections_and_their_updates_count() {
        wav("det_r1.wav")
        wav("det_r2.wav")
        File(dir, "detections.rescan-20260421-100000.jsonl").writeText(det("r1", "memos/det_r1.wav") + "\n" + det("r2", null) + "\n")
        // Zusatzdatei der Variante: r2 bekommt ein Memo, r1 verliert seines
        File(dir, "detections.updates.rescan-20260421-100000.jsonl").writeText(det("r2", "memos/det_r2.wav") + "\n" + det("r1", null) + "\n")
        val p = paths()
        assertTrue("memos/det_r2.wav" in p)
        assertTrue("memos/det_r1.wav" !in p)
    }

    @Test fun memo_in_undo_window_is_left_out_until_undone() {
        wav("session_20260420-143500.wav")
        File(dir, "memos.jsonl").writeText(memoLine("memos/session_20260420-143500.wav"))
        PendingMemoDeletes.add(dir.name, "memos/session_20260420-143500.wav")
        try {
            assertTrue("memos/session_20260420-143500.wav" !in paths())
        } finally {
            PendingMemoDeletes.remove(dir.name, "memos/session_20260420-143500.wav")
        }
        assertTrue("memos/session_20260420-143500.wav" in paths())
    }

    @Test fun keep_referenced_works_on_generic_entries_saf_like() {
        val memos = memoLine("memos/session_x.wav")
        fun e(p: String, t: String = "") = ExportEntry(p) { ByteArrayInputStream(t.toByteArray()) }
        val out = SessionExportRules.keepReferencedMemos(
            listOf(e("memos.jsonl", memos), e("memos/session_x.wav"), e("memos/other.wav"), e("recording.wav")), "S1"
        ).map { it.path }
        assertEquals(listOf("memos.jsonl", "memos/session_x.wav", "recording.wav"), out)
    }

    @Test fun lines_of_file_restore_removed_entry() {
        val text = memoLine("memos/session_a.wav") + memoLine("memos/session_b.wav") + "kaputt\n"
        val removed = SessionMemos.linesOfFile(text, "memos/session_a.wav")
        val without = SessionMemos.withoutFile(text, "memos/session_a.wav")
        assertEquals(memoLine("memos/session_a.wav"), removed)
        assertEquals(listOf("memos/session_b.wav", "memos/session_a.wav"), SessionMemos.parse(without + removed).map { it.file })
        assertEquals("", SessionMemos.linesOfFile(text, "memos/fehlt.wav"))
        assertEquals("", SessionMemos.linesOfFile(null, "memos/session_a.wav"))
    }

    // T305 (N1): Art-Memo nur in der Zusatzdatei (laufende Session); die Zusatzdatei selbst wird nicht exportiert
    @Test fun art_memo_referenced_only_in_updates_file_is_exported() {
        wav("det_a.wav")
        wav("det_x.wav")  // verwaist
        File(dir, "detections.jsonl").writeText(det("a", null) + "\n")
        File(dir, "detections.updates.jsonl").writeText(det("a", "memos/det_a.wav") + "\n")
        val p = paths()
        assertTrue("memos/det_a.wav" in p)
        assertTrue("memos/det_x.wav" !in p)
        assertTrue("detections.updates.jsonl" !in p)
    }
}
