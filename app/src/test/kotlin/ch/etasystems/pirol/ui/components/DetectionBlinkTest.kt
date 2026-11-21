package ch.etasystems.pirol.ui.components

import ch.etasystems.pirol.ml.DetectionListState
import ch.etasystems.pirol.ml.DetectionResult
import ch.etasystems.pirol.ml.VerificationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T178: welche Aenderung blinkt, welche nicht. */
class DetectionBlinkTest {

    private fun det(id: String, name: String = "Turdus merula") = DetectionResult(
        id = id, scientificName = name, commonName = name, confidence = 0.8f,
        timestampMs = System.currentTimeMillis(), chunkStartSec = 0f, chunkEndSec = 3f, sampleRate = 48000
    )

    @Test
    fun frischerTreffer_blinkt() {
        assertTrue(DetectionBlink.shouldBlink(10_000L, 0L, 10_500L))
    }

    @Test
    fun keinTreffer_keinBlinken() {
        assertFalse(DetectionBlink.shouldBlink(0L, 0L, 10_500L))
    }

    @Test
    fun bereitsBehandelterTreffer_blinktNichtNochmal() {
        assertFalse(DetectionBlink.shouldBlink(10_000L, 10_000L, 10_500L))
        assertTrue(DetectionBlink.shouldBlink(12_000L, 10_000L, 12_100L))
    }

    @Test
    fun nachholenNurInnerhalbDreiSekunden() {
        assertTrue(DetectionBlink.shouldBlink(10_000L, 0L, 13_000L))
        assertFalse(DetectionBlink.shouldBlink(10_000L, 0L, 13_001L))
    }

    @Test
    fun neueUndErneuteErkennung_setzenSignal_verifikationNicht() {
        val state = DetectionListState()
        state.addDetections(listOf(det("a")))
        val first = state.lastHitMs("a")
        assertTrue(first > 0L)

        // Verifikation und Edit aendern das Signal nicht
        state.updateVerification("a", VerificationStatus.CONFIRMED)
        assertEquals(first, state.lastHitMs("a"))
        state.updateDetection(state.getDetections().first().copy(note = "x"))
        assertEquals(first, state.lastHitMs("a"))

        // Erneute Erkennung derselben Art setzt das Signal neu
        Thread.sleep(5)
        state.addDetections(listOf(det("b")))
        assertTrue(state.lastHitMs("a") > first)

        state.clear()
        assertEquals(0L, state.lastHitMs("a"))
    }

    @Test
    fun clusterMerge_setztSignalAufAnker() {
        val state = DetectionListState()
        state.addOrCluster(det("a"), clusterEnabled = true, radiusM = 0, timeWindowMin = 10)
        val first = state.lastHitMs("a")
        Thread.sleep(5)
        state.addOrCluster(det("b"), clusterEnabled = true, radiusM = 0, timeWindowMin = 10)
        assertTrue(state.lastHitMs("a") > first)
        assertEquals(0L, state.lastHitMs("b"))
    }
}
