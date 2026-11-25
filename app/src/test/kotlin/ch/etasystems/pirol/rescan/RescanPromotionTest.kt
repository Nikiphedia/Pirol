package ch.etasystems.pirol.rescan

import ch.etasystems.pirol.data.export.RavenExporter
import ch.etasystems.pirol.data.repository.FileSessionFiles
import ch.etasystems.pirol.data.repository.ImportedSource
import ch.etasystems.pirol.data.repository.RecordingSegment
import ch.etasystems.pirol.data.repository.SessionJson
import ch.etasystems.pirol.data.repository.SessionMetadata
import ch.etasystems.pirol.data.repository.SessionTextParser
import ch.etasystems.pirol.data.repository.SpeciesFilterInfo
import ch.etasystems.pirol.ml.DetectionResult
import ch.etasystems.pirol.ml.VerificationEvent
import ch.etasystems.pirol.ml.VerificationStatus
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** T273: erster Scan einer importierten Session wird zum Original; Mehrdatei-Offsets. */
class RescanPromotionTest {

    @get:Rule val tmp = TemporaryFolder()

    private val id = "20261125-101500"

    private val segments = listOf(
        RecordingSegment("recording.wav", 0f, 60f, "2026-05-01T05:00:00+02:00"),
        RecordingSegment("recording-002.wav", 60f, 60f, "2026-05-01T05:10:00+02:00")
    )

    private fun meta(imported: Boolean = true) = SessionMetadata(
        sessionId = "2026-05-01T05-00-00_abcdef",
        startedAt = "2026-05-01T05:00:00+02:00",
        endedAt = "2026-05-01T05:11:00+02:00",
        sampleRate = 48000,
        confidenceThreshold = 0.5f,
        recordingSegments = segments,
        importedFrom = if (imported) listOf(
            ImportedSource("a.wav", "wav", 48000, 1, 60f, "2026-05-01T05:00:00+02:00", "filename"),
            ImportedSource("b.wav", "wav", 48000, 1, 60f, "2026-05-01T05:10:00+02:00", "filename")
        ) else null
    )

    private fun info(status: String = RescanStatus.DONE, vid: String = id) = RescanInfo(
        id = vid, createdAt = "2026-11-25T10:15:00+01:00", modelId = "v3_final_fp16", modelName = "BirdNET 3.0",
        regionFilter = "groups", confidenceThreshold = 0.3f, overlapPercent = 0,
        speciesFilter = SpeciesFilterInfo(source = "list", groups = mapOf("birds" to "ch_all")),
        detectionCount = 2, status = status
    )

    private fun det(did: String, start: Float) = DetectionResult(
        id = did, scientificName = "Turdus merula", commonName = "Amsel", confidence = 0.8f,
        timestampMs = 1_000L, chunkStartSec = start, chunkEndSec = start + 3f, sampleRate = 48000
    )

    private val detText = listOf(det("a", 12f), det("b", 75f)).joinToString("") { SessionJson.compact.encodeToString(it) + "\n" }
    private val verText = SessionJson.compact.encodeToString(
        VerificationEvent("a", VerificationStatus.CONFIRMED, verifiedAtMs = 5L, actionType = VerificationEvent.ACTION_CONFIRMED)) + "\n"

    /** Importierte Session nach dem Scan: Original leer, Variante fertig (DONE) */
    private fun session(imported: Boolean = true): FileSessionFiles {
        val f = FileSessionFiles(tmp.newFolder())
        f.writeReplacing("session.json", SessionJson.pretty.encodeToString(meta(imported)))
        f.writeReplacing(RescanFiles.DETECTIONS, "")
        f.writeReplacing(RescanFiles.detections(id), detText)
        f.writeReplacing(RescanFiles.verifications(id), verText)
        f.writeReplacing(RescanFiles.raven(id), "raven")
        RescanStore.writeMeta(f, info())
        return f
    }

    private fun loadMeta(f: FileSessionFiles) = SessionJson.lenient.decodeFromString<SessionMetadata>(f.readText("session.json")!!)

    private fun assertPromoted(f: FileSessionFiles) {
        assertEquals(detText, f.readText(RescanFiles.DETECTIONS))
        assertEquals(verText, f.readText(RescanFiles.VERIFICATIONS))
        RescanFiles.filesOf(id).forEach { assertFalse(it, f.exists(it)) }
        val m = loadMeta(f)
        assertEquals("v3_final_fp16", m.modelId)
        assertEquals(2, m.totalDetections)
        assertEquals(0.3f, m.confidenceThreshold, 0f)
        assertEquals(mapOf("birds" to "ch_all"), m.speciesFilter?.groups)
        assertEquals("2026-05-01T05-00-00_abcdef", m.sessionId)
        assertEquals(segments, m.recordingSegments)  // Offsets/Segmente unveraendert
    }

    // ── Regel ────────────────────────────────────────────────────────

    @Test fun first_scan_of_imported_session_is_promoted() {
        assertTrue(RescanPromotion.shouldPromote(meta(), id, "", null, listOf(info())))
        assertTrue(RescanPromotion.shouldPromote(meta(), id, null, "  \n", listOf(info(), info(RescanStatus.ABORTED, "20261125-090000"))))
    }

    @Test fun later_scans_and_live_sessions_stay_variants() {
        assertFalse(RescanPromotion.shouldPromote(meta(imported = false), id, "", null, listOf(info())))
        assertFalse(RescanPromotion.shouldPromote(meta(), id, detText, null, listOf(info())))
        assertFalse(RescanPromotion.shouldPromote(meta(), id, "", verText, listOf(info())))
        assertFalse(RescanPromotion.shouldPromote(meta(), id, "", null, listOf(info(), info(vid = "20261124-090000"))))
        assertFalse(RescanPromotion.shouldPromote(null, id, "", null, listOf(info())))
    }

    // ── Ausfuehrung und Wiederherstellung ────────────────────────────

    @Test fun promote_moves_variant_into_original() {
        val f = session()
        assertTrue(RescanPromotion.promote(f, info()))
        assertPromoted(f)
    }

    @Test fun resume_after_marker_only() {
        val f = session()
        RescanStore.writeMeta(f, info(RescanPromotion.PROMOTING))
        val pending = RescanPromotion.pending(RescanStore.list(f))
        assertEquals(listOf(id), pending.map { it.id })
        assertTrue(RescanPromotion.promote(f, pending.single()))
        assertPromoted(f)
    }

    @Test fun resume_after_copy_before_cleanup() {
        val f = session()
        RescanStore.writeMeta(f, info(RescanPromotion.PROMOTING))
        f.writeReplacing(RescanFiles.DETECTIONS, detText)  // Abbruch nach dem Kopieren der Detektionen
        assertTrue(RescanPromotion.promote(f, RescanStore.list(f).single()))
        assertPromoted(f)
    }

    @Test fun resume_after_partial_delete() {
        val f = session()
        RescanStore.writeMeta(f, info(RescanPromotion.PROMOTING))
        f.writeReplacing(RescanFiles.DETECTIONS, detText)
        f.writeReplacing(RescanFiles.VERIFICATIONS, verText)
        f.delete(RescanFiles.detections(id))  // Abbruch beim Aufraeumen: Quelle schon weg
        assertTrue(RescanPromotion.promote(f, RescanStore.list(f).single()))
        assertPromoted(f)
    }

    @Test fun never_overwrites_user_data_in_original() {
        val f = session()
        f.writeReplacing(RescanFiles.VERIFICATIONS, "andere\n")
        RescanStore.writeMeta(f, info(RescanPromotion.PROMOTING))
        assertFalse(RescanPromotion.promote(f, RescanStore.list(f).single()))
        assertEquals("andere\n", f.readText(RescanFiles.VERIFICATIONS))
        assertEquals("", f.readText(RescanFiles.DETECTIONS))
        assertEquals(RescanStatus.DONE, RescanStore.list(f).single().status)  // Variante bleibt sichtbar
        assertEquals(detText, f.readText(RescanFiles.detections(id)))
    }

    // ── Mehrere Dateien: Session-Achse ueber alle Segmente ───────────

    @Test fun multi_file_scan_covers_all_segments_and_raven_begin_file() {
        val sr = 100
        val total = (120 * sr).toLong()
        val starts = RescanPlan.windowStarts(total, sr, 3000, 0)
        assertEquals(40, starts.size)
        assertTrue(starts.last() >= 60 * sr)  // zweites Segment wird gescannt
        val f = session()
        RescanPromotion.promote(f, info())
        val dets = SessionTextParser.detections(f.readText(RescanFiles.DETECTIONS)!!)
        val out = File(tmp.root, "raven.txt")
        RavenExporter.export(out, dets, loadMeta(f).startedAt, loadMeta(f).recordingSegments)
        val rows = out.readLines().map { it.split('\t') }
        val file = rows[0].indexOf("Begin File")
        val begin = rows[0].indexOf("Begin Time (s)")
        val dt = rows[0].indexOf("Begin Date Time")
        assertEquals("recording.wav", rows[1][file])
        assertEquals("12.000", rows[1][begin])
        assertEquals("recording-002.wav", rows[2][file])
        assertEquals("15.000", rows[2][begin])  // 75 s Session-Achse = 15 s im zweiten Segment
        assertEquals("2026-05-01T05:10:15+02:00", rows[2][dt])
    }
}
