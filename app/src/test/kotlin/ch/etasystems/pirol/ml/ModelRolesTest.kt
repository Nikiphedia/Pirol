package ch.etasystems.pirol.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** T223: Rollenwahl Primaer/Sekundaer und Loeschbarkeit */
class ModelRolesTest {

    @Test fun secondaryTapSelects() {
        assertEquals("b.onnx", ModelRoles.toggleSecondary(primary = "a.onnx", secondary = null, tapped = "b.onnx"))
    }

    @Test fun secondaryTapAgainDeselects() {
        assertNull(ModelRoles.toggleSecondary("a.onnx", "b.onnx", "b.onnx"))
    }

    @Test fun secondaryTapOtherReplaces() {
        assertEquals("c.onnx", ModelRoles.toggleSecondary("a.onnx", "b.onnx", "c.onnx"))
    }

    @Test fun secondaryCannotBePrimary() {
        assertEquals("b.onnx", ModelRoles.toggleSecondary("a.onnx", "b.onnx", "a.onnx"))
        assertNull(ModelRoles.toggleSecondary("a.onnx", null, "a.onnx"))
    }

    @Test fun newPrimaryClearsEqualSecondary() {
        assertNull(ModelRoles.secondaryAfterPrimaryChange("b.onnx", "b.onnx"))
        assertEquals("c.onnx", ModelRoles.secondaryAfterPrimaryChange("b.onnx", "c.onnx"))
        assertNull(ModelRoles.secondaryAfterPrimaryChange("b.onnx", null))
    }

    @Test fun deleteRules() {
        assertEquals(ModelRoles.DeleteBlock.NONE, ModelRoles.deleteBlock("b.onnx", "a.onnx", recording = false))
        assertEquals(ModelRoles.DeleteBlock.PRIMARY, ModelRoles.deleteBlock("a.onnx", "a.onnx", recording = false))
        assertEquals(ModelRoles.DeleteBlock.RECORDING, ModelRoles.deleteBlock("b.onnx", "a.onnx", recording = true))
        // Primaer hat Vorrang vor Aufnahme (Hinweis ist dauerhaft gueltig)
        assertEquals(ModelRoles.DeleteBlock.PRIMARY, ModelRoles.deleteBlock("a.onnx", "a.onnx", recording = true))
    }

    @Test fun secondaryAfterDelete() {
        assertNull(ModelRoles.secondaryAfterDelete("b.onnx", "b.onnx"))
        assertEquals("c.onnx", ModelRoles.secondaryAfterDelete("b.onnx", "c.onnx"))
    }
}
