package ch.etasystems.pirol.ui.analysis

import ch.etasystems.pirol.audio.PlaybackKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** T303 (Befund 9): Zeiger bei Memo-Wiedergabe, auch wenn das Art-Memo ueber die Artkarte gestartet wurde. */
class MemoPlayheadTest {

    private fun item(key: String, start: Float?, detectionId: String? = null) = MemoItem(
        key = key, file = "memos/$key.wav", startMs = null, endMs = null, approximate = start == null,
        detectionId = detectionId, scientificName = null, sessionStartSec = start, sessionEndSec = null
    )

    private val items = listOf(item(MemoItem.sessionKey("memos/session_a.wav"), 100f), item("det1", null, "det1"), item("det2", 40f, "det2"))

    @Test fun non_memo_playback_keeps_player_position() {
        assertEquals(12.5f, PlaybackNav.playheadSec(12.5f, PlaybackKeys.SESSION, null) { items })
        assertEquals(12.5f, PlaybackNav.playheadSec(12.5f, null, null) { items })
    }

    @Test fun remembered_memo_block_start_wins() {
        val key = PlaybackKeys.memo(MemoItem.sessionKey("memos/session_a.wav"))
        assertEquals(103f, PlaybackNav.playheadSec(3f, key, key to 100f) { error("nicht noetig") })
    }

    @Test fun art_memo_from_card_without_session_position_shows_no_pointer() {
        // Artkarte startet memo:det1, zuletzt gemerkt war ein anderes Memo: nie die Position in der Memo-Datei zeigen
        val remembered = PlaybackKeys.memo(MemoItem.sessionKey("memos/session_a.wav")) to 100f
        assertNull(PlaybackNav.playheadSec(3f, PlaybackKeys.memo("det1"), remembered) { items })
        assertNull(PlaybackNav.playheadSec(3f, PlaybackKeys.memo("unbekannt"), null) { items })
    }

    @Test fun art_memo_from_card_with_known_position_uses_it() {
        assertEquals(42f, PlaybackNav.playheadSec(2f, PlaybackKeys.memo("det2"), null) { items })
    }

    @Test fun memo_key_detection() {
        assertEquals(true, PlaybackNav.isMemoKey(PlaybackKeys.memo("x")))
        assertEquals(false, PlaybackNav.isMemoKey(PlaybackKeys.SESSION))
        assertEquals(false, PlaybackNav.isMemoKey(null))
    }
}
