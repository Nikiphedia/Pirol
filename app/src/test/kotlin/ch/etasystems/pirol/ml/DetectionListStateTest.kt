package ch.etasystems.pirol.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DetectionListStateTest {

    @Test
    fun `updateVerification returns true and changes status`() {
        val state = DetectionListState()
        state.addDetections(listOf(makeDetection("id-1", VerificationStatus.UNVERIFIED)))

        val result = state.updateVerification("id-1", VerificationStatus.CONFIRMED)

        assertTrue(result)
        assertEquals(
            VerificationStatus.CONFIRMED,
            state.getDetections().first { it.id == "id-1" }.verificationStatus
        )
    }

    @Test
    fun `updateVerification returns false for unknown id`() {
        val state = DetectionListState()
        assertFalse(state.updateVerification("nonexistent", VerificationStatus.CONFIRMED))
    }

    @Test
    fun `updateVerification preserves correctedSpecies only for CORRECTED`() {
        val state = DetectionListState()
        state.addDetections(listOf(makeDetection("id-1", VerificationStatus.UNVERIFIED)))

        state.updateVerification("id-1", VerificationStatus.CORRECTED, "Parus major")
        assertEquals("Parus major", state.getDetections().first().correctedSpecies)

        state.updateVerification("id-1", VerificationStatus.CONFIRMED)
        assertEquals(null, state.getDetections().first().correctedSpecies)
    }

    @Test
    fun `updateVerification race — no corruption, final status is valid`() {
        val state = DetectionListState()
        state.addDetections(listOf(makeDetection("id-1", VerificationStatus.UNVERIFIED)))

        val latch = CountDownLatch(2)
        val t1 = Thread {
            repeat(500) { state.updateVerification("id-1", VerificationStatus.CONFIRMED) }
            latch.countDown()
        }
        val t2 = Thread {
            repeat(500) { state.updateVerification("id-1", VerificationStatus.REJECTED) }
            latch.countDown()
        }
        t1.start()
        t2.start()
        assertTrue(latch.await(5, TimeUnit.SECONDS))

        val finalStatus = state.getDetections().first { it.id == "id-1" }.verificationStatus
        assertTrue(
            finalStatus == VerificationStatus.CONFIRMED || finalStatus == VerificationStatus.REJECTED
        )
    }

    private fun makeDetection(id: String, status: VerificationStatus) = DetectionResult(
        id = id,
        scientificName = "Turdus merula",
        commonName = "Amsel",
        confidence = 0.9f,
        timestampMs = 0L,
        chunkStartSec = 0f,
        chunkEndSec = 3f,
        sampleRate = 48000,
        verificationStatus = status
    )
}
