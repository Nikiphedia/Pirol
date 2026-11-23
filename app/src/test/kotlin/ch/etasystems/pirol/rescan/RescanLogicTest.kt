package ch.etasystems.pirol.rescan

import ch.etasystems.pirol.ml.DetectionResult
import ch.etasystems.pirol.ml.SpeciesKey
import ch.etasystems.pirol.ml.VerificationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** T221: Dateinamen, Fensterplan, Zeitbasis und Verifikations-Uebernahme des Rescans. */
class RescanLogicTest {

    private val zone = ZoneId.of("Europe/Zurich")

    private fun det(
        id: String, species: String, start: Float, end: Float = start + 3f,
        status: VerificationStatus = VerificationStatus.UNVERIFIED, corrected: String? = null,
        verifiedAt: Long? = null, ts: Long = 1_000_000L + (start * 1000).toLong(), lastSeen: Long = ts,
        manual: Boolean = false
    ) = DetectionResult(
        id = id, scientificName = species, commonName = species, confidence = 0.9f,
        timestampMs = ts, chunkStartSec = start, chunkEndSec = end, sampleRate = 48000,
        verificationStatus = status, correctedSpecies = corrected, verifiedAtMs = verifiedAt,
        lastSeenAtMs = lastSeen, manualEntry = manual
    )

    // ── Dateinamen ──

    @Test
    fun fileNames_originalVsVariant() {
        assertEquals("detections.jsonl", RescanFiles.detections(null))
        assertEquals("verifications.jsonl", RescanFiles.verifications(null))
        assertEquals("recording.selections.txt", RescanFiles.raven(null))
        assertEquals("detections.rescan-20261123-143000.jsonl", RescanFiles.detections("20261123-143000"))
        assertEquals("verifications.rescan-20261123-143000.jsonl", RescanFiles.verifications("20261123-143000"))
        assertEquals("recording.rescan-20261123-143000.selections.txt", RescanFiles.raven("20261123-143000"))
        assertEquals("rescan-20261123-143000.json", RescanFiles.meta("20261123-143000"))
        // Keine Variante darf eine Original-Datei treffen
        assertTrue(RescanFiles.filesOf("20261123-143000").none { it in setOf("detections.jsonl", "verifications.jsonl", "recording.selections.txt", "session.json") })
    }

    @Test
    fun newId_localTime_andCollisionSuffix() {
        val ms = LocalDateTime.of(2026, 11, 23, 14, 30, 5).atZone(zone).toInstant().toEpochMilli()
        assertEquals("20261123-143005", RescanFiles.newId(ms, zone, emptyList()))
        assertEquals("20261123-143005-2", RescanFiles.newId(ms, zone, listOf("20261123-143005")))
        assertEquals("20261123-143005-3", RescanFiles.newId(ms, zone, listOf("20261123-143005", "20261123-143005-2")))
    }

    @Test
    fun idsIn_onlyMetaFiles_sorted() {
        val names = listOf(
            "session.json", "detections.jsonl", "rescan-20261123-150000.json", "detections.rescan-20261123-160000.jsonl",
            "rescan-20261123-143005.json", "rescan-20261123-143005-2.json", "rescan-kaputt.json", "recording.wav"
        )
        assertEquals(listOf("20261123-143005", "20261123-143005-2", "20261123-150000"), RescanFiles.idsIn(names))
    }

    // ── Fensterplan ──

    @Test
    fun windowStarts_overlapRaster_onlyFullWindows() {
        val sr = 48000
        // 10 s Aufnahme, 3-s-Fenster
        assertEquals(listOf(0L, 144000L, 288000L), RescanPlan.windowStarts(10L * sr, sr, 3000, 0))
        // 50 %: Hop 1.5 s → Starts 0..7 s
        val half = RescanPlan.windowStarts(10L * sr, sr, 3000, 50)
        assertEquals(5, half.size)
        assertEquals(72000L, half[1])
        // 67 %: Hop 1 s → Starts 0..7 s
        val third = RescanPlan.windowStarts(10L * sr, sr, 3000, 67)
        assertEquals(8, third.size)
        assertEquals(48000L, third[1])
        // Overlap relativ zum Fenster (5-s-Modell, 50 % = 2.5 s)
        assertEquals(listOf(0L, 120000L, 240000L), RescanPlan.windowStarts(10L * sr, sr, 5000, 50))
        // kuerzer als ein Fenster → nichts
        assertTrue(RescanPlan.windowStarts(2L * sr, sr, 3000, 0).isEmpty())
    }

    @Test
    fun epochAtZero_fromOriginals_ignoresManual_elseStartedAt() {
        val o = listOf(
            det("a", "Turdus_merula", 6f, ts = 50_000L),       // 50000 - 6000 = 44000
            det("b", "Turdus_merula", 12f, ts = 56_000L),      // 44000
            det("m", "Turdus_merula", 30f, ts = 999_999L, manual = true)
        )
        assertEquals(44_000L, RescanPlan.epochAtZero(o, 99L))
        assertEquals(99L, RescanPlan.epochAtZero(emptyList(), 99L))
        assertNull(RescanPlan.epochAtZero(emptyList(), null))
    }

    @Test
    fun positionAt_nearestTrackPoint_elseFallback() {
        val track = listOf(Triple(1000L, 47.0, 8.0), Triple(5000L, 47.1, 8.1))
        assertEquals(47.0 to 8.0, RescanPlan.positionAt(2000L, track, null))
        assertEquals(47.1 to 8.1, RescanPlan.positionAt(4000L, track, null))
        assertEquals(46.0 to 7.0, RescanPlan.positionAt(4000L, emptyList(), 46.0 to 7.0))
    }

    // ── Verifikations-Uebernahme (AC2) ──

    @Test
    fun transfer_sameSpeciesAndOverlap() {
        val orig = listOf(det("o1", "Turdus merula", 3f, status = VerificationStatus.CONFIRMED, verifiedAt = 777L))
        val res = listOf(det("r1", "Turdus_merula", 4.5f))
        val ev = RescanVerification.transfer(res, orig, 1L)
        assertEquals(1, ev.size)
        assertEquals("r1", ev[0].detectionId)
        assertEquals(VerificationStatus.CONFIRMED, ev[0].status)
        assertEquals(777L, ev[0].verifiedAtMs)
        assertEquals("rescan_from:o1", ev[0].newValue)
    }

    @Test
    fun transfer_noTransferForOtherSpecies_noOverlap_orUnverified() {
        val orig = listOf(
            det("o1", "Turdus_merula", 3f, status = VerificationStatus.REJECTED, verifiedAt = 1L),
            det("o2", "Erithacus_rubecula", 30f, status = VerificationStatus.CONFIRMED, verifiedAt = 1L),
            det("o3", "Parus_major", 60f)  // unverifiziert
        )
        val res = listOf(
            det("r1", "Erithacus_rubecula", 3f),   // andere Art im Fenster von o1
            det("r2", "Turdus_merula", 6f),        // gleiche Art, beruehrt nur (6 = Ende von o1)
            det("r3", "Parus_major", 60f)          // Original unverifiziert
        )
        assertTrue(RescanVerification.transfer(res, orig, 1L).isEmpty())
    }

    @Test
    fun transfer_correctedStatusCarried_andCorrectionTargetConfirmed() {
        val orig = listOf(det("o1", "Turdus_merula", 3f, status = VerificationStatus.CORRECTED, corrected = "Turdus_philomelos", verifiedAt = 5L))
        val res = listOf(det("r1", "Turdus_merula", 3f), det("r2", "Turdus philomelos", 4f))
        val ev = RescanVerification.transfer(res, orig, 1L).associateBy { it.detectionId }
        assertEquals(VerificationStatus.CORRECTED, ev.getValue("r1").status)
        assertEquals("Turdus_philomelos", ev.getValue("r1").correctedSpecies)
        assertEquals(VerificationStatus.CONFIRMED, ev.getValue("r2").status)
        assertNull(ev.getValue("r2").correctedSpecies)
    }

    @Test
    fun transfer_largestOverlapWins_andClusterSpanCounts() {
        val orig = listOf(
            det("o1", "Turdus_merula", 0f, status = VerificationStatus.REJECTED, verifiedAt = 1L),
            // Cluster von 9 s bis 21 s (letztes Fenster 9 s nach dem ersten)
            det("o2", "Turdus_merula", 9f, status = VerificationStatus.CONFIRMED, verifiedAt = 2L,
                ts = 10_000L, lastSeen = 19_000L)
        )
        val res = listOf(det("r1", "Turdus_merula", 2f), det("r2", "Turdus_merula", 15f))
        val ev = RescanVerification.transfer(res, orig, 1L).associateBy { it.detectionId }
        assertEquals(VerificationStatus.REJECTED, ev.getValue("r1").status)   // 1 s mit o1, 0 mit o2
        assertEquals(VerificationStatus.CONFIRMED, ev.getValue("r2").status)  // liegt im Cluster o2
    }

    @Test
    fun transfer_matchesViaSpeciesAlias() {
        SpeciesKey.setAliases(mapOf("Parus caeruleus" to "Cyanistes caeruleus"))
        try {
            val orig = listOf(det("o1", "Parus_caeruleus", 3f, status = VerificationStatus.UNCERTAIN, verifiedAt = 1L))
            val ev = RescanVerification.transfer(listOf(det("r1", "Cyanistes_caeruleus", 3f)), orig, 1L)
            assertEquals(VerificationStatus.UNCERTAIN, ev.single().status)
        } finally {
            SpeciesKey.setAliases(emptyMap())
        }
    }
}
