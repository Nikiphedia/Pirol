package ch.etasystems.pirol.ui.map

import ch.etasystems.pirol.data.repository.SessionMemos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/** T272: Nur Session-Memos mit Position erscheinen als Marker auf der Karte. */
class MemoMapMarkersTest {
    private val zone = ZoneId.of("Europe/Zurich")

    @Test
    fun memos_without_fix_are_skipped() {
        val withFix = SessionMemos.create("session_a.wav", 1_000_000L, 1_005_000L, 3f, zone, 47.37, 8.54, 6f)
        val noFix = SessionMemos.create("session_b.wav", 2_000_000L, 2_005_000L, 9f, zone)
        val markers = MemoMapMarkers.of("s1", listOf(withFix, noFix))
        assertEquals(1, markers.size)
        assertEquals("memos/session_a.wav", markers[0].file)
        assertEquals(47.37, markers[0].latitude, 1e-9)
        assertEquals(6f, markers[0].accuracyM!!, 1e-6f)
    }

    @Test
    fun empty_list_gives_no_markers() {
        assertTrue(MemoMapMarkers.of("s1", emptyList()).isEmpty())
    }
}
