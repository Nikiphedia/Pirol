package ch.etasystems.pirol.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T168: Zaehler, Nachlauf und Sample-Ersetzung des Mute-Gates. */
class PlaybackMuteGateTest {

    private var now = 10_000L
    private val gate = PlaybackMuteGate { now }

    @Test
    fun `ohne Wiedergabe nicht gemutet`() {
        assertFalse(gate.isMuted())
        assertFalse(gate.playbackActive.value)
    }

    @Test
    fun `waehrend Wiedergabe gemutet`() {
        gate.acquire()
        now += 60_000
        assertTrue(gate.isMuted())
        assertTrue(gate.playbackActive.value)
    }

    @Test
    fun `verschachtelte Player erst nach letztem release frei`() {
        gate.acquire(); gate.acquire()
        gate.release()
        assertTrue(gate.isMuted())
        assertTrue(gate.playbackActive.value)
        gate.release()
        assertFalse(gate.playbackActive.value)
        assertTrue(gate.isMuted())  // Nachlauf
    }

    @Test
    fun `Nachlauf 500 ms nach Ende`() {
        gate.acquire()
        gate.release()
        now += PlaybackMuteGate.TAIL_MS - 1
        assertTrue(gate.isMuted())
        now += 1
        assertFalse(gate.isMuted())
    }

    @Test
    fun `ueberzaehliges release (Fehlerpfad) wird ignoriert`() {
        gate.release(); gate.release()
        assertFalse(gate.isMuted())
        gate.acquire()
        assertTrue(gate.isMuted())  // Zaehler nicht negativ geworden
        gate.release()
        assertFalse(gate.playbackActive.value)
    }

    @Test
    fun `Samples werden durch Stille ersetzt, Laenge bleibt`() {
        val s = ShortArray(4800) { 1000 }
        assertFalse(gate.muteIfNeeded(s))
        assertTrue(s.all { it == 1000.toShort() })
        gate.acquire()
        assertTrue(gate.muteIfNeeded(s))
        assertEquals(4800, s.size)
        assertTrue(s.all { it == 0.toShort() })
    }

    @Test
    fun `Chunk nach Nachlauf bleibt unveraendert`() {
        gate.acquire(); gate.release()
        now += PlaybackMuteGate.TAIL_MS
        val s = ShortArray(100) { 7 }
        assertFalse(gate.muteIfNeeded(s))
        assertEquals(7.toShort(), s[0])
    }

    @Test
    fun `T182 Preroll-Historie wird waehrend Wiedergabe und Nachlauf stumm`() {
        // 1 kHz Rate: 1 Sample = 1 ms. Fenster 10_000..20_000 ms.
        val rate = 1000
        now = 12_000; gate.acquire()
        now = 14_000; gate.release()      // stumm 12_000..14_500
        now = 20_000
        val s = ShortArray(10_000) { 5 }
        val zeroed = gate.muteHistory(s, rate, endMs = 20_000)
        assertEquals(2500, zeroed)
        assertEquals(5.toShort(), s[1999])
        assertEquals(0.toShort(), s[2000])
        assertEquals(0.toShort(), s[4499])
        assertEquals(5.toShort(), s[4500])
    }

    @Test
    fun `T182 laufende Wiedergabe mutet bis Fensterende, ohne Wiedergabe nichts`() {
        val s = ShortArray(1000) { 5 }
        assertEquals(0, gate.muteHistory(s, 1000, endMs = now))
        now = 10_000; gate.acquire()
        now = 10_600
        assertEquals(600, gate.muteHistory(s, 1000, endMs = now))
        assertEquals(0.toShort(), s[999])
    }
}
