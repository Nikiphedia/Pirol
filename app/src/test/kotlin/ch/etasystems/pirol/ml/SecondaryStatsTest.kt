package ch.etasystems.pirol.ml

import ch.etasystems.pirol.ml.SecondaryStats.Secondary
import ch.etasystems.pirol.ml.SecondaryStats.Verification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SecondaryStatsTest {
    private var n = 0
    private fun det(v: VerificationStatus, sec: String?, model: String? = "m1", manual: Boolean = false) =
        DetectionResult(
            id = "d${n++}", scientificName = "Parus_major", commonName = "x", confidence = 0.9f,
            timestampMs = 0L, chunkStartSec = 0f, chunkEndSec = 3f, sampleRate = 48000,
            verificationStatus = v, secondaryStatus = sec, secondaryModelId = model, manualEntry = manual
        )

    @Test fun crossTabCountsAndSums() {
        val t = SecondaryStats.count(listOf(
            det(VerificationStatus.CONFIRMED, "CHECKED"),
            det(VerificationStatus.CONFIRMED, "DISAGREES"),
            det(VerificationStatus.REJECTED, "DISAGREES"),
            det(VerificationStatus.REJECTED, "DISAGREES"),
            det(VerificationStatus.UNVERIFIED, null, model = null),
            det(VerificationStatus.CORRECTED, "NOT_CHECKABLE"),
            det(VerificationStatus.UNCERTAIN, "CHECKED")
        )).total
        assertEquals(7, t.total)
        assertEquals(2, t.rowSum(Verification.CONFIRMED))
        assertEquals(1, t.get(Verification.OPEN, Secondary.NONE))
        assertEquals(1, t.get(Verification.OTHER, Secondary.NOT_CHECKABLE))
        assertEquals(3, t.colSum(Secondary.DISAGREES))
        assertEquals(1, t.withResult(Verification.UNCERTAIN))
    }

    @Test fun disagreeRates() {
        val t = SecondaryStats.count(listOf(
            det(VerificationStatus.CONFIRMED, "CHECKED"),
            det(VerificationStatus.CONFIRMED, "CHECKED"),
            det(VerificationStatus.CONFIRMED, "CHECKED"),
            det(VerificationStatus.CONFIRMED, "DISAGREES"),
            det(VerificationStatus.CONFIRMED, "NOT_CHECKABLE"),
            det(VerificationStatus.REJECTED, "DISAGREES"),
            det(VerificationStatus.REJECTED, "CHECKED")
        )).total
        assertEquals(1.0 / 5, t.disagreeRate(Verification.CONFIRMED)!!, 1e-9)
        assertEquals(1.0 / 4, t.disagreeRateCheckable(Verification.CONFIRMED)!!, 1e-9)
        assertEquals(0.5, t.disagreeRate(Verification.REJECTED)!!, 1e-9)
        assertNull(t.disagreeRate(Verification.UNCERTAIN))
    }

    @Test fun manualEntriesIgnoredAndPerModelSeparated() {
        val r = SecondaryStats.count(listOf(
            det(VerificationStatus.CONFIRMED, "CHECKED", "a"),
            det(VerificationStatus.CONFIRMED, "CHECKED", "b"),
            det(VerificationStatus.CONFIRMED, null, null),
            det(VerificationStatus.CONFIRMED, null, null, manual = true)
        ))
        assertEquals(3, r.total.total)
        assertEquals(setOf("a", "b"), r.perModel.keys)
        assertEquals(1, r.perModel.getValue("a").total)
    }

    @Test fun progressAndCsv() {
        assertEquals(0.5f, SecondaryStats.progress(100, 200), 0f)
        assertEquals(1f, SecondaryStats.progress(300, 200), 0f)
        val csv = SecondaryStats.toCsv(SecondaryStats.count(listOf(det(VerificationStatus.REJECTED, "DISAGREES"))))
        assertTrue(csv.lines()[0].startsWith("model;verification"))
        assertTrue(csv.contains("all;rejected;0;0;1;0;1"))
        assertTrue(csv.contains("m1;rejected;0;0;1;0;1"))
    }
}
