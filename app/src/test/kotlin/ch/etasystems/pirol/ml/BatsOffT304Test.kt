package ch.etasystems.pirol.ml

import ch.etasystems.pirol.data.FeatureFlags
import ch.etasystems.pirol.data.sync.UploadQueueProbe
import androidx.work.WorkInfo.State
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T304: Fledermaus-Schalter aus (kein "bats"-Schluessel, Modelle zuruecksetzen); Upload-Warteschlange. */
class BatsOffT304Test {

    @After fun reset() { FeatureFlags.bats = false }

    private val sel = SpeciesGroupSelection(mapOf(TaxonGroup.BIRDS to GroupSource.AUTO, TaxonGroup.BATS to GroupSource.ALL))

    @Test fun `Schalter aus - bats nicht in toMap und encode`() {
        FeatureFlags.bats = false
        assertFalse(sel.toMap().containsKey("bats"))
        assertFalse(sel.encode().contains("bats"))
    }

    @Test fun `Schalter an - bats bleibt`() {
        FeatureFlags.bats = true
        assertEquals(GroupSource.ALL, sel.toMap()["bats"])
    }

    @Test fun `Ultraschall-Primaer wird Standard, Sekundaer entfaellt`() {
        val us = setOf("bat.onnx", "bat2.onnx")
        val r = ModelRoles.afterBatsOff("bat.onnx", "bat2.onnx", "birdnet_v3.onnx") { it in us }
        assertEquals(ModelRoles.Roles("birdnet_v3.onnx", null), r)
    }

    @Test fun `normale Modelle bleiben, nur Ultraschall-Sekundaer entfaellt`() {
        val r = ModelRoles.afterBatsOff("a.onnx", "bat.onnx", "birdnet_v3.onnx") { it == "bat.onnx" }
        assertEquals(ModelRoles.Roles("a.onnx", null), r)
        val r2 = ModelRoles.afterBatsOff("a.onnx", "b.onnx", "birdnet_v3.onnx") { false }
        assertEquals(ModelRoles.Roles("a.onnx", "b.onnx"), r2)
    }

    @Test fun `Sekundaer gleich neues Primaer wird geleert`() {
        val r = ModelRoles.afterBatsOff("bat.onnx", "birdnet_v3.onnx", "birdnet_v3.onnx") { it == "bat.onnx" }
        assertEquals(null, r.secondary)
    }

    @Test fun `Upload offen bei ENQUEUED RUNNING BLOCKED`() {
        assertTrue(UploadQueueProbe.isPending(listOf(State.SUCCEEDED, State.ENQUEUED)))
        assertTrue(UploadQueueProbe.isPending(listOf(State.RUNNING)))
        assertTrue(UploadQueueProbe.isPending(listOf(State.BLOCKED)))
        assertFalse(UploadQueueProbe.isPending(listOf(State.SUCCEEDED, State.FAILED, State.CANCELLED)))
        assertFalse(UploadQueueProbe.isPending(emptyList()))
    }

    // T305 (N2): Rueckfall = Standardmodell, sonst erstes Nicht-Ultraschall-Modell
    @Test fun `Rueckfall Standardmodell wenn installiert`() {
        val r = ModelRoles.fallbackPrimary(listOf("bat.onnx", "other.onnx", "std.onnx"), "std.onnx") { it == "bat.onnx" }
        assertEquals("std.onnx", r)
    }

    @Test fun `Rueckfall erstes Nicht-Ultraschall-Modell ohne Standardmodell`() {
        val r = ModelRoles.fallbackPrimary(listOf("bat.onnx", "z.onnx", "b.onnx"), "std.onnx") { it == "bat.onnx" }
        assertEquals("b.onnx", r)
        assertEquals("std.onnx", ModelRoles.fallbackPrimary(listOf("bat.onnx"), "std.onnx") { true })
    }
}
