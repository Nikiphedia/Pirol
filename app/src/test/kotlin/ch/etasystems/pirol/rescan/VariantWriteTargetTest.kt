package ch.etasystems.pirol.rescan

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** T303 (Befunde 3 und 4): Schreibziel nach Uebernahme/Loeschen einer Variante; Abbruch in der Schlussphase. */
class VariantWriteTargetTest {

    @Test fun original_stays_original() {
        assertEquals(VariantWriteTarget.Target.Write(null), VariantWriteTarget.resolve(null, metaExists = false, promoted = false))
    }

    @Test fun existing_variant_is_written() {
        assertEquals(VariantWriteTarget.Target.Write("20260421-100000"),
            VariantWriteTarget.resolve("20260421-100000", metaExists = true, promoted = false))
        // auch waehrend der Uebernahme (PROMOTING, Metadaten noch da): Schreiben wartet auf die Sperre, dann neu pruefen
        assertEquals(VariantWriteTarget.Target.Write("20260421-100000"),
            VariantWriteTarget.resolve("20260421-100000", metaExists = true, promoted = true))
    }

    @Test fun promoted_variant_redirects_to_original() {
        assertEquals(VariantWriteTarget.Target.Write(null),
            VariantWriteTarget.resolve("20260421-100000", metaExists = false, promoted = true))
    }

    @Test fun deleted_variant_drops_action_and_creates_nothing() {
        assertEquals(VariantWriteTarget.Target.Drop,
            VariantWriteTarget.resolve("20260421-100000", metaExists = false, promoted = false))
    }

    @Test fun promoted_registry_per_session() {
        val a = File("/s/2026-04-20/A")
        val b = File("/s/2026-04-20/B")
        PromotedVariants.add(a, "20260421-100000")
        assertTrue(PromotedVariants.contains(a, "20260421-100000"))
        assertFalse(PromotedVariants.contains(b, "20260421-100000"))
        assertFalse(PromotedVariants.contains(a, "20260421-100001"))
    }

    @Test fun cancelled_job_still_reports_promotion() {
        var result: Boolean? = null
        runBlocking {
            launch {
                coroutineContext.job.cancel()  // Abbruch (Knopf/onTimeout) nach erledigter Uebernahme
                result = PromotionCheck.isPromoted(RescanStatus.DONE, recorded = false) {
                    withContext(Dispatchers.IO) { true }  // wie withSessionFiles: Metadaten fehlen
                }
            }.join()
        }
        assertEquals(true, result)
    }

    @Test fun recorded_promotion_needs_no_io_and_errors_mean_false() {
        runBlocking {
            assertTrue(PromotionCheck.isPromoted(RescanStatus.DONE, recorded = true) { error("nicht aufrufen") })
            assertFalse(PromotionCheck.isPromoted(RescanStatus.DONE, recorded = false) { throw java.io.IOException("x") })
            assertFalse(PromotionCheck.isPromoted(RescanStatus.ABORTED, recorded = true) { true })
        }
    }
}
