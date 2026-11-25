package ch.etasystems.pirol.rescan

import ch.etasystems.pirol.data.repository.FileSessionFiles
import ch.etasystems.pirol.data.repository.SessionJson
import ch.etasystems.pirol.data.repository.SessionMetadata
import ch.etasystems.pirol.ml.DetectionResult
import ch.etasystems.pirol.ml.VerificationEvent
import ch.etasystems.pirol.ml.VerificationStatus
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** T306: vollstaendiger Scan wird Original, wenn das Original leer ist (auch mit aelteren Varianten, beim Oeffnen). */
class RescanPromotionEmptyOriginalTest {

    @get:Rule val tmp = TemporaryFolder()

    private val older = "20261124-075558"
    private val newer = "20261125-184042"
    private val aborted = "20261125-185248"

    private fun meta(imported: Boolean) = SessionMetadata(
        sessionId = "2026-09-12T15-43-39_210649",
        startedAt = "2026-09-12T15:43:39+02:00",
        sampleRate = 48000,
        confidenceThreshold = 0.5f,
        importedFrom = if (imported) emptyList() else null
    )

    private fun info(vid: String, status: String = RescanStatus.DONE, scanned: Float? = 600f) = RescanInfo(
        id = vid, createdAt = "2026-11-25T18:40:42+01:00", modelId = "m_$vid", modelName = "BirdNET 3.0",
        regionFilter = "groups", confidenceThreshold = 0.3f, overlapPercent = 0,
        status = status, scannedSec = scanned, totalSec = 600f
    )

    private fun det(did: String) = DetectionResult(
        id = did, scientificName = "Turdus merula", commonName = "Amsel", confidence = 0.8f,
        timestampMs = 1_000L, chunkStartSec = 3f, chunkEndSec = 6f, sampleRate = 48000
    )

    private fun detText(vid: String) = SessionJson.compact.encodeToString(det("d_$vid")) + "\n"
    private fun verText(vid: String) = SessionJson.compact.encodeToString(
        VerificationEvent("d_$vid", VerificationStatus.CONFIRMED, verifiedAtMs = 5L, actionType = VerificationEvent.ACTION_CONFIRMED)) + "\n"

    private fun addVariant(f: FileSessionFiles, i: RescanInfo) {
        f.writeReplacing(RescanFiles.detections(i.id), detText(i.id))
        f.writeReplacing(RescanFiles.verifications(i.id), verText(i.id))
        RescanStore.writeMeta(f, i)
    }

    /** Befund-Session: Original 0 Byte, aeltere DONE, neuere DONE, ABORTED */
    private fun session(imported: Boolean = true, vararg variants: RescanInfo): FileSessionFiles {
        val f = FileSessionFiles(tmp.newFolder())
        f.writeReplacing("session.json", SessionJson.pretty.encodeToString(meta(imported)))
        f.writeReplacing(RescanFiles.DETECTIONS, "")
        variants.forEach { addVariant(f, it) }
        return f
    }

    private fun modelId(f: FileSessionFiles) =
        SessionJson.lenient.decodeFromString<SessionMetadata>(f.readText("session.json")!!).modelId

    @Test fun new_done_scan_is_promoted_despite_older_done_variant() {
        val f = session(true, info(older), info(newer))
        assertTrue(RescanPromotion.promoteIfEmpty(f, info(newer)))
        assertEquals(detText(newer), f.readText(RescanFiles.DETECTIONS))
        assertEquals(verText(newer), f.readText(RescanFiles.VERIFICATIONS))  // Verifikationen gehen mit
        assertEquals(listOf(older), RescanStore.list(f).map { it.id })  // aeltere bleibt Variante
        assertEquals("m_$newer", modelId(f))
    }

    @Test fun live_session_without_detections_is_promoted_too() {
        val f = session(false, info(newer))
        assertTrue(RescanPromotion.promoteIfEmpty(f, info(newer)))
        assertEquals(detText(newer), f.readText(RescanFiles.DETECTIONS))
    }

    @Test fun only_aborted_promotes_nothing() {
        val f = session(true, info(aborted, RescanStatus.ABORTED, scanned = 120f))
        assertFalse(RescanPromotion.promoteIfEmpty(f, RescanStore.list(f).single()))
        assertNull(RescanPromotion.promoteNewestIfEmpty(f, null))
        assertEquals("", f.readText(RescanFiles.DETECTIONS))
        assertEquals(listOf(aborted), RescanStore.list(f).map { it.id })
    }

    @Test fun on_open_newest_done_is_chosen() {
        val f = session(true, info(older), info(newer), info(aborted, RescanStatus.ABORTED, scanned = 120f))
        val picked = RescanPromotion.promoteNewestIfEmpty(f, null)
        assertEquals(newer, picked?.id)
        assertEquals(detText(newer), f.readText(RescanFiles.DETECTIONS))
        assertEquals(verText(newer), f.readText(RescanFiles.VERIFICATIONS))
        assertEquals(listOf(older, aborted), RescanStore.list(f).map { it.id })
    }

    @Test fun on_open_incomplete_done_is_skipped_old_variant_without_seconds_counts() {
        val legacy = info(older, scanned = null).copy(totalSec = null)
        val partial = info(newer, scanned = 300f)  // DONE, aber nicht ganz gescannt
        val f = session(true, legacy, partial)
        assertEquals(older, RescanPromotion.promoteNewestIfEmpty(f, null)?.id)
    }

    @Test fun original_not_empty_promotes_nothing() {
        val f = session(true, info(newer))
        f.writeReplacing(RescanFiles.DETECTIONS, detText("orig"))
        assertNull(RescanPromotion.promoteNewestIfEmpty(f, null))
        assertFalse(RescanPromotion.promoteIfEmpty(f, info(newer)))
        val g = session(true, info(newer))
        g.writeReplacing(RescanFiles.VERIFICATIONS, verText("orig"))
        assertNull(RescanPromotion.promoteNewestIfEmpty(g, null))
        assertEquals(listOf(newer), RescanStore.list(g).map { it.id })
    }

    @Test fun running_or_pending_promotion_blocks_open_promotion() {
        val f = session(true, info(older), info(newer, RescanStatus.RUNNING, scanned = 10f))
        assertNull(RescanPromotion.promoteNewestIfEmpty(f, newer))
        assertEquals("", f.readText(RescanFiles.DETECTIONS))
        val g = session(true, info(older), info(newer, RescanPromotion.PROMOTING))
        assertNull(RescanPromotion.promoteNewestIfEmpty(g, null))  // erst Wiederherstellung (variants)
    }

    @Test fun repeated_open_is_idempotent() {
        val f = session(true, info(older), info(newer))
        assertEquals(newer, RescanPromotion.promoteNewestIfEmpty(f, null)?.id)
        assertNull(RescanPromotion.promoteNewestIfEmpty(f, null))
        assertNull(RescanPromotion.promoteNewestIfEmpty(f, null))
        assertEquals(detText(newer), f.readText(RescanFiles.DETECTIONS))
        assertEquals(listOf(older), RescanStore.list(f).map { it.id })
    }

    @Test fun crash_during_open_promotion_resumes_same_variant() {
        val f = session(true, info(older), info(newer))
        // Abbruch nach Marke + Kopie der Detektionen
        RescanStore.writeMeta(f, info(newer, RescanPromotion.PROMOTING))
        f.writeReplacing(RescanFiles.DETECTIONS, detText(newer))
        assertNull(RescanPromotion.promoteNewestIfEmpty(f, null))  // Original belegt / PROMOTING -> nicht neu waehlen
        val pending = RescanPromotion.pending(RescanStore.list(f)).single()
        assertTrue(RescanPromotion.promote(f, pending))  // Wiederherstellung wie RescanEngine.variants
        assertEquals(detText(newer), f.readText(RescanFiles.DETECTIONS))
        assertEquals(verText(newer), f.readText(RescanFiles.VERIFICATIONS))
        assertEquals(listOf(older), RescanStore.list(f).map { it.id })
        assertNull(RescanPromotion.promoteNewestIfEmpty(f, null))
    }
}
