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

    private fun marker(sid: String, file: String) =
        MemoMapMarker(sid, file, 47.0, 8.0, 1L, null)

    @Test
    fun append_adds_and_skips_duplicates() {
        val a = listOf(marker("s1", "memos/a.wav"))
        val r = MemoMapMarkers.append(a, listOf(marker("s1", "memos/a.wav"), marker("s1", "memos/b.wav")))
        assertEquals(listOf("memos/a.wav", "memos/b.wav"), r.map { it.file })
        // gleiche Datei in anderer Session ist kein Duplikat
        assertEquals(2, MemoMapMarkers.append(a, listOf(marker("s2", "memos/a.wav"))).size)
    }

    @Test
    fun remove_drops_only_matching_marker() {
        val l = listOf(marker("s1", "memos/a.wav"), marker("s2", "memos/a.wav"))
        assertEquals(listOf("s2"), MemoMapMarkers.remove(l, "s1", "memos/a.wav").map { it.sessionId })
    }

    @Test
    fun live_only_keeps_current_session() {
        val l = listOf(marker("s1", "memos/a.wav"), marker("s2", "memos/b.wav"))
        assertEquals(listOf("s2"), MemoMapMarkers.forFilter(FilterMode.LIVE_ONLY, "s2", l).map { it.sessionId })
        assertTrue(MemoMapMarkers.forFilter(FilterMode.LIVE_ONLY, null, l).isEmpty())
        assertEquals(2, MemoMapMarkers.forFilter(FilterMode.ALL, "s2", l).size)
    }
}
