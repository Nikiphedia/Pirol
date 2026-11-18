package ch.etasystems.pirol.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit-Tests fuer den Watchlist-Abgleich und die Alarm-Pause (T124). */
class WatchlistMatcherTest {

    private val entries = listOf(
        WatchlistEntry("Turdus_merula", priority = WatchlistPriority.high),
        WatchlistEntry("Luscinia megarhynchos", priority = WatchlistPriority.normal)
    )

    @Test
    fun `unterstrich und leerzeichen sind gleichwertig`() {
        assertEquals(WatchlistPriority.high, WatchlistMatcher.findEntry(entries, "Turdus merula")?.priority)
        assertNotNull(WatchlistMatcher.findEntry(entries, "Luscinia_megarhynchos"))
    }

    @Test
    fun `gross und kleinschreibung sowie whitespace egal`() {
        assertNotNull(WatchlistMatcher.findEntry(entries, " turdus_MERULA "))
    }

    @Test
    fun `unbekannte art liefert null`() {
        assertNull(WatchlistMatcher.findEntry(entries, "Parus major"))
    }

    @Test
    fun `cooldown ohne letzten alarm ist frei`() {
        assertTrue(WatchlistMatcher.isCooldownOver(null, 300_000L, 1_000L))
    }

    @Test
    fun `cooldown aktiv und abgelaufen`() {
        assertFalse(WatchlistMatcher.isCooldownOver(1_000L, 300_000L, 100_000L))
        assertTrue(WatchlistMatcher.isCooldownOver(1_000L, 300_000L, 301_000L))
    }

    @Test
    fun `cooldown null deaktiviert die pause`() {
        assertTrue(WatchlistMatcher.isCooldownOver(1_000L, 0L, 1_001L))
    }

    @Test
    fun `matchAndCheckCooldown toleriert schreibweise`() {
        val watched = setOf("Turdus_merula")
        assertTrue(WatchlistManager.matchAndCheckCooldown("turdus_merula", watched, 0L, emptyMap(), 1L))
    }
}
