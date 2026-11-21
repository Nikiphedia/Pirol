package ch.etasystems.pirol.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T173: Controller-Logik gegen einen Fake-Player (kein Android noetig). */
class PlaybackControllerTest {

    private class FakeEngine : PlaybackEngine {
        val s = MutableStateFlow(AudioPlayer.PlaybackState.IDLE)
        override val state: StateFlow<AudioPlayer.PlaybackState> = s
        var stops = 0
        override fun stop() { stops++; s.value = AudioPlayer.PlaybackState.IDLE }
        fun startPlaying() { s.value = AudioPlayer.PlaybackState.PLAYING }
    }

    private fun controller(engine: FakeEngine): PlaybackController {
        // Unconfined: Flow-Collector laufen synchron mit den Zustandswechseln des Fake-Players
        return PlaybackController(engine, CoroutineScope(Dispatchers.Unconfined))
    }

    @Test
    fun toggleStartsAndSameKeyStops() {
        val e = FakeEngine()
        val c = controller(e)
        assertFalse(c.toggle("a") { e.startPlaying() })
        assertEquals("a", c.playingKey.value)
        assertTrue(c.toggle("a") { e.startPlaying() })  // gleicher Schluessel = Stopp
        assertNull(c.playingKey.value)
        assertEquals(AudioPlayer.PlaybackState.IDLE, e.s.value)
    }

    @Test
    fun otherKeySwitchesPlayback() {
        val e = FakeEngine()
        val c = controller(e)
        c.toggle("a") { e.startPlaying() }
        c.toggle("b") { e.startPlaying() }
        assertEquals("b", c.playingKey.value)
        assertTrue(c.isPlaying("b"))
        assertFalse(c.isPlaying("a"))
    }

    @Test
    fun endOfPlaybackClearsKey() {
        val e = FakeEngine()
        val c = controller(e)
        c.play("a") { e.startPlaying() }
        e.s.value = AudioPlayer.PlaybackState.IDLE  // Ausschnitt zu Ende
        assertNull(c.playingKey.value)
    }

    @Test
    fun failedStartLeavesNoKey() {
        val e = FakeEngine()
        val c = controller(e)
        c.play("a") { /* Datei fehlt: Player bleibt IDLE */ }
        assertNull(c.playingKey.value)
    }

    @Test
    fun detectionKeyRoundTrip() {
        assertEquals("x1", PlaybackKeys.detectionIdOf(PlaybackKeys.detection("x1")))
        assertNull(PlaybackKeys.detectionIdOf(PlaybackKeys.memo("x1")))
        assertNull(PlaybackKeys.detectionIdOf(null))
    }
}
