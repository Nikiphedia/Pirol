package ch.etasystems.pirol.ml

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelReleasePolicyTest {
    private val d = 45_000L

    @Test fun gibtNachVerzoegerungFrei() =
        assertTrue(ModelReleasePolicy.shouldRelease(true, false, 0, d, d))

    @Test fun nichtVorVerzoegerung() =
        assertFalse(ModelReleasePolicy.shouldRelease(true, false, 0, d - 1, d))

    @Test fun nieWaehrendAufnahme() =
        assertFalse(ModelReleasePolicy.shouldRelease(true, true, 0, 10 * d, d))

    @Test fun nieImVordergrund() =
        assertFalse(ModelReleasePolicy.shouldRelease(false, false, 0, 10 * d, d))
}
