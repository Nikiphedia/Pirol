package ch.etasystems.pirol.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Unit-Tests fuer die manuelle Art-Hinzufuege-Funktion (T72).
 *
 * Prueft: addManualDetection() bypass, manualEntry-Flag, Cluster-Bypass,
 * sowie die ACTION_MANUAL_ADD / ACTION_ALTERNATIVE_CONFIRMED Konstanten.
 */
class ManualAddDetectionTest {

    private fun makeDet(
        id: String = UUID.randomUUID().toString(),
        species: String = "Turdus merula",
        manual: Boolean = false,
        confidence: Float = 0.8f
    ) = DetectionResult(
        id = id,
        scientificName = species,
        commonName = "Amsel",
        confidence = confidence,
        timestampMs = System.currentTimeMillis(),
        chunkStartSec = 0f,
        chunkEndSec = 0f,
        sampleRate = 0,
        manualEntry = manual
    )

    @Test
    fun `addManualDetection fuegt Detection an Index 0 ein`() {
        val state = DetectionListState()
        val det = makeDet(manual = true, confidence = 1.0f)
        state.addManualDetection(det)
        assertEquals(1, state.size)
        assertEquals(det.id, state.getDetections().first().id)
    }

    @Test
    fun `addManualDetection fuegt Detection vor bestehenden ein`() {
        val state = DetectionListState()
        val existing = makeDet(id = "existing", species = "Parus major")
        state.addDetections(listOf(existing))
        val manual = makeDet(id = "manual", species = "Turdus merula", manual = true)
        state.addManualDetection(manual)
        assertEquals(2, state.size)
        assertEquals("manual", state.getDetections().first().id)
    }

    @Test
    fun `addManualDetection bypasses Cluster — gleiche Art zweimal ergibt zwei Eintraege`() {
        val state = DetectionListState()
        val det1 = makeDet(id = "a", species = "Turdus merula", manual = true)
        val det2 = makeDet(id = "b", species = "Turdus merula", manual = true)
        state.addManualDetection(det1)
        state.addManualDetection(det2)
        // addManualDetection macht KEIN Dedup — beide muessen vorhanden sein
        assertEquals(2, state.size)
        assertTrue(state.getDetections().any { it.id == "a" })
        assertTrue(state.getDetections().any { it.id == "b" })
    }

    @Test
    fun `addManualDetection — manualEntry=true bleibt erhalten`() {
        val state = DetectionListState()
        val det = makeDet(manual = true)
        state.addManualDetection(det)
        assertTrue(state.getDetections().first().manualEntry)
    }

    @Test
    fun `addDetections mit manualEntry=false setzt manualEntry nicht`() {
        val state = DetectionListState()
        val det = makeDet(manual = false)
        state.addDetections(listOf(det))
        assertFalse(state.getDetections().first().manualEntry)
    }

    @Test
    fun `ACTION_MANUAL_ADD Konstante hat erwarteten Wert`() {
        assertEquals("MANUAL_ADD", VerificationEvent.ACTION_MANUAL_ADD)
    }

    @Test
    fun `ACTION_ALTERNATIVE_CONFIRMED Konstante hat erwarteten Wert`() {
        assertEquals("ALTERNATIVE_CONFIRMED", VerificationEvent.ACTION_ALTERNATIVE_CONFIRMED)
    }
}
