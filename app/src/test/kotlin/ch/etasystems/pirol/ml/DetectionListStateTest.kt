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

    // ── T73: Lock-Species Tests ────────────────────────────────────────────────

    @Test
    fun `T73 lock-toggle — updateDetection setzt lockSpecies und isLocked gibt true zurueck`() {
        val state = DetectionListState()
        state.addDetections(listOf(makeDetection("id-lock", VerificationStatus.UNVERIFIED)))

        val det = state.getDetections().first { it.id == "id-lock" }
        val locked = det.copy(lockSpecies = true)
        state.updateDetection(locked)

        val speciesKey = det.scientificName.replace(' ', '_')
        state.rebuildLockedSpecies()

        assertTrue(state.getDetections().first { it.id == "id-lock" }.lockSpecies)
        assertTrue(state.isLocked(speciesKey))
        assertTrue(state.lockedSpecies.value.contains(speciesKey))
    }

    @Test
    fun `T73 suppression — isLocked gibt false fuer nicht gesperrte Art`() {
        val state = DetectionListState()
        state.addDetections(listOf(makeDetection("id-free", VerificationStatus.UNVERIFIED)))

        val speciesKey = "Turdus_merula"
        assertFalse(state.isLocked(speciesKey))
    }

    @Test
    fun `T73 unlock — nach Entsperren ist isLocked false`() {
        val state = DetectionListState()
        state.addDetections(listOf(makeDetection("id-u", VerificationStatus.UNVERIFIED)))

        // Sperren
        val det = state.getDetections().first { it.id == "id-u" }
        state.updateDetection(det.copy(lockSpecies = true))
        state.rebuildLockedSpecies()
        val speciesKey = det.scientificName.replace(' ', '_')
        assertTrue(state.isLocked(speciesKey))

        // Entsperren
        state.updateDetection(det.copy(lockSpecies = false))
        state.rebuildLockedSpecies()
        assertFalse(state.isLocked(speciesKey))
    }

    @Test
    fun `T73 sort-locked-first — gesperrte Art steht in getDetections() zuerst`() {
        val state = DetectionListState()
        val now = System.currentTimeMillis()
        // Neuere unlocked Art (hoeher lastSeenAtMs)
        state.addDetections(listOf(
            makeDetection("id-free", VerificationStatus.UNVERIFIED).copy(lastSeenAtMs = now + 1000)
        ))
        // Aeltere locked Art
        val lockedDet = makeDetection("id-locked", VerificationStatus.UNVERIFIED)
            .copy(scientificName = "Parus major", commonName = "Kohlmeise", lastSeenAtMs = now)
        state.addDetections(listOf(lockedDet))
        state.updateDetection(lockedDet.copy(lockSpecies = true))

        val detections = state.getDetections()
        assertTrue(
            "Gesperrte Art muss an Index 0 stehen",
            detections[0].lockSpecies
        )
        assertEquals("id-locked", detections[0].id)
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
