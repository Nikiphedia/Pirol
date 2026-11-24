package ch.etasystems.pirol.data.repository

import ch.etasystems.pirol.ml.DetectionResult
import ch.etasystems.pirol.ml.VerificationEvent
import ch.etasystems.pirol.ml.VerificationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** T259: Nur echte Verifikationen setzen Status/Korrektur/verifiedAtMs. */
class ApplyVerificationsTest {

    private val d = DetectionResult(
        id = "a", scientificName = "Turdus merula", commonName = "Amsel", confidence = 0.9f,
        timestampMs = 0L, chunkStartSec = 0f, chunkEndSec = 3f, sampleRate = 48000, count = 2, note = "x"
    )

    private fun ev(status: VerificationStatus, action: String?, corrected: String? = null, at: Long = 1L, newValue: String? = null) =
        VerificationEvent("a", status, corrected, at, action, newValue)

    private fun apply(vararg e: VerificationEvent) = SessionTextParser.applyVerifications(listOf(d), e.toList()).single()

    @Test fun clusterMerged_keepsVerification() {
        val r = apply(
            ev(VerificationStatus.CORRECTED, null, "Turdus philomelos", at = 5L),
            ev(VerificationStatus.UNVERIFIED, VerificationEvent.ACTION_CLUSTER_MERGED,
                "merged:b|count:2|lastSeenAt:9|conf:0.5000|start:3.000", at = 9L)
        )
        assertEquals(VerificationStatus.CORRECTED, r.verificationStatus)
        assertEquals("Turdus philomelos", r.correctedSpecies)
        assertEquals(5L, r.verifiedAtMs)
    }

    @Test fun clusterMergedAlone_noPayloadInCorrected() {
        val r = apply(ev(VerificationStatus.UNVERIFIED, VerificationEvent.ACTION_CLUSTER_MERGED, "merged:b|count:2|lastSeenAt:9"))
        assertNull(r.correctedSpecies)
        assertNull(r.verifiedAtMs)
        assertEquals(VerificationStatus.UNVERIFIED, r.verificationStatus)
    }

    @Test fun metaEvents_doNotResetStatus_fieldsFromDetectionLine() {
        val r = apply(
            ev(VerificationStatus.CONFIRMED, null, at = 5L),
            ev(VerificationStatus.UNVERIFIED, VerificationEvent.ACTION_COUNT_CHANGED, at = 6L),
            ev(VerificationStatus.UNVERIFIED, VerificationEvent.ACTION_ATLAS_SET, at = 7L),
            ev(VerificationStatus.UNVERIFIED, VerificationEvent.ACTION_NOTE_ADDED, at = 7L),
            ev(VerificationStatus.UNVERIFIED, VerificationEvent.ACTION_MEMO_RECORDED, at = 8L, newValue = "memos/a.wav"),
            ev(VerificationStatus.UNVERIFIED, VerificationEvent.ACTION_MEMO_DELETED, at = 8L),
            ev(VerificationStatus.UNVERIFIED, VerificationEvent.ACTION_SPECIES_LOCKED, at = 8L, newValue = "true"),
            ev(VerificationStatus.UNVERIFIED, VerificationEvent.ACTION_SIMULTANEOUS_LINK, at = 8L, newValue = "b"),
            ev(VerificationStatus.UNVERIFIED, VerificationEvent.ACTION_SAME_INDIVIDUAL, at = 8L, newValue = "b")
        )
        assertEquals(VerificationStatus.CONFIRMED, r.verificationStatus)
        assertEquals(5L, r.verifiedAtMs)
        assertEquals(2, r.count)
        assertEquals("x", r.note)
    }

    @Test fun positionMoved_onlyPosition() {
        val r = apply(
            ev(VerificationStatus.REJECTED, null, at = 5L),
            ev(VerificationStatus.UNVERIFIED, VerificationEvent.ACTION_POSITION_MOVED, at = 6L, newValue = "47.5,8.25")
        )
        assertEquals(VerificationStatus.REJECTED, r.verificationStatus)
        assertEquals(47.5, r.latitude!!, 1e-9)
    }

    @Test fun verifyingActions_stillApply() {
        assertEquals(VerificationStatus.CONFIRMED,
            apply(ev(VerificationStatus.CONFIRMED, VerificationEvent.ACTION_ALTERNATIVE_CONFIRMED, newValue = "Parus major")).verificationStatus)
        assertEquals(VerificationStatus.CONFIRMED,
            apply(ev(VerificationStatus.CONFIRMED, VerificationEvent.ACTION_MANUAL_ADD, newValue = "species:x|count:1")).verificationStatus)
        val undo = apply(
            ev(VerificationStatus.REJECTED, null, at = 5L),
            ev(VerificationStatus.UNCERTAIN, VerificationEvent.ACTION_UNDO, at = 6L)
        )
        assertEquals(VerificationStatus.UNCERTAIN, undo.verificationStatus)
        val rescan = apply(ev(VerificationStatus.CORRECTED, null, "Parus major", newValue = "rescan_from:o1"))
        assertEquals("Parus major", rescan.correctedSpecies)
    }
}
