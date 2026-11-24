package ch.etasystems.pirol.location

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** T262: Entscheidungslogik Offline-Treffer / Online-Rueckfall. */
class MunicipalityResolveTest {

    private fun run(enabled: Boolean, offline: String?, online: String?): Pair<String?, Int> {
        var onlineCalls = 0
        val r = runBlocking {
            resolveMunicipality(enabled, { offline }, { onlineCalls++; online })
        }
        return r to onlineCalls
    }

    @Test fun offlineHitSkipsOnline() = assertEquals("Bern BE" to 0, run(true, "Bern BE", "X"))
    @Test fun noHitFallbackOn() = assertEquals("Lyon FR" to 1, run(true, null, "Lyon FR"))
    @Test fun noHitFallbackOff() = assertEquals(null to 0, run(false, null, "Lyon FR"))
}
