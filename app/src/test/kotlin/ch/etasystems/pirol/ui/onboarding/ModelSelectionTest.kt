package ch.etasystems.pirol.ui.onboarding

import ch.etasystems.pirol.ml.ModelProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelSelectionTest {
    private val catalog = ModelProfile.CATALOG

    @Test fun formatSpeciesCount_apostroph() {
        assertEquals("11'725", ModelSelection.formatSpeciesCount(11725))
        assertEquals("11'560", ModelSelection.formatSpeciesCount(11560))
        assertEquals("999", ModelSelection.formatSpeciesCount(999))
        assertEquals("1'000", ModelSelection.formatSpeciesCount(1000))
    }

    @Test fun queue_katalogReihenfolge() {
        val ids = setOf(catalog[2].id, catalog[0].id)
        val q = ModelSelection.queue(ids, catalog)
        assertEquals(listOf(catalog[0].id, catalog[2].id), q.map { it.id })
    }

    @Test fun queue_leer() {
        assertEquals(emptyList<ModelProfile>(), ModelSelection.queue(emptySet(), catalog))
    }

    @Test fun pickActive_bevorzugtV3Final() {
        val ok = listOf(ModelProfile.PREVIEW31_FP16, ModelProfile.V3_FINAL_FP16)
        assertEquals(ModelProfile.V3_FINAL_FP16.id, ModelSelection.pickActive(ok, ModelProfile.V3_FINAL_FP16.id)?.id)
    }

    @Test fun pickActive_sonstErstes() {
        val ok = listOf(ModelProfile.PREVIEW31_FP16)
        assertEquals(ModelProfile.PREVIEW31_FP16.id, ModelSelection.pickActive(ok, ModelProfile.V3_FINAL_FP16.id)?.id)
        assertNull(ModelSelection.pickActive(emptyList(), "x"))
    }
}
