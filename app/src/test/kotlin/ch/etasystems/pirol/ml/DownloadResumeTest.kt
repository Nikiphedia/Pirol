package ch.etasystems.pirol.ml

import ch.etasystems.pirol.ml.DownloadResume.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadResumeTest {

    @Test fun rangeHeader_nurMitOffset() {
        assertNull(DownloadResume.rangeHeader(0))
        assertEquals("bytes=138000000-", DownloadResume.rangeHeader(138_000_000))
    }

    @Test fun decide_206_haengtAn() {
        assertEquals(Action.APPEND, DownloadResume.decide(206, 100))
    }

    @Test fun decide_200_mitOffset_startetNeu() {
        assertEquals(Action.FROM_START, DownloadResume.decide(200, 100))
        assertEquals(Action.FROM_START, DownloadResume.decide(200, 0))
    }

    @Test fun decide_416_und_fehler() {
        assertEquals(Action.DISCARD_AND_RETRY, DownloadResume.decide(416, 100))
        assertEquals(Action.REJECT, DownloadResume.decide(416, 0))
        assertEquals(Action.REJECT, DownloadResume.decide(206, 0))
        assertEquals(Action.REJECT, DownloadResume.decide(403, 0))
        assertEquals(Action.REJECT, DownloadResume.decide(429, 100))
    }

    @Test fun startOffset_plausibilitaet() {
        assertEquals(0L, DownloadResume.startOffset(0, 1000))
        assertEquals(500L, DownloadResume.startOffset(500, 1000))
        assertEquals(1000L, DownloadResume.startOffset(1000, 1000))
        assertEquals(0L, DownloadResume.startOffset(1500, 1000))
        assertEquals(500L, DownloadResume.startOffset(500, null))
    }

    @Test fun isComplete() {
        assertTrue(DownloadResume.isComplete(1000, 1000))
        assertFalse(DownloadResume.isComplete(999, 1000))
        assertFalse(DownloadResume.isComplete(0, 0))
        assertFalse(DownloadResume.isComplete(1000, null))
    }

    @Test fun totalBytes() {
        assertEquals(1000L, DownloadResume.totalBytes(1000, 400, 600))
        assertEquals(1000L, DownloadResume.totalBytes(null, 400, 600))
        assertEquals(401L, DownloadResume.totalBytes(null, 400, null))
    }

    @Test fun neededFreeBytes_zaehltTeildateiNichtDoppelt() {
        val mb = 1_048_576L
        assertEquals(173 * mb + 50 * mb, DownloadResume.neededFreeBytes(173 * mb, null, 0, 50 * mb))
        assertEquals(35 * mb + 1 * mb + 50 * mb, DownloadResume.neededFreeBytes(173 * mb, 1 * mb, 138 * mb, 50 * mb))
        // unplausible Teildatei (groesser als Modell) → volle Groesse
        assertEquals(100L + 10L, DownloadResume.neededFreeBytes(100, null, 500, 10))
    }

    @Test fun backoff_waechst() {
        assertEquals(2000L, DownloadResume.backoffMillis(1))
        assertEquals(4000L, DownloadResume.backoffMillis(2))
    }
}
