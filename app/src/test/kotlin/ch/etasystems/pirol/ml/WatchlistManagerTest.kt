package ch.etasystems.pirol.ml

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit-Tests fuer WatchlistManager.matchAndCheckCooldown() (AC8, T58).
 * Testet die reine Logik-Funktion im Companion Object — kein Android-Context noetig.
 */
class WatchlistManagerTest {

    private val watchedSet = setOf("Turdus_merula", "Luscinia_megarhynchos")
    private val cooldown5min = 5 * 60_000L
    private val now = 1_000_000L

    @Test
    fun `match und cooldown frei loest alarm aus`() {
        val lastAlarmTimes = emptyMap<String, Long>()
        assertTrue(
            WatchlistManager.matchAndCheckCooldown(
                "Turdus_merula", watchedSet, cooldown5min, lastAlarmTimes, now
            )
        )
    }

    @Test
    fun `match aber cooldown aktiv unterdrueckt alarm`() {
        val lastAlarmTimes = mapOf("Turdus_merula" to now - 60_000L)  // vor 1 Minute
        assertFalse(
            WatchlistManager.matchAndCheckCooldown(
                "Turdus_merula", watchedSet, cooldown5min, lastAlarmTimes, now
            )
        )
    }

    @Test
    fun `keine watchlist art loest nie alarm aus`() {
        val lastAlarmTimes = emptyMap<String, Long>()
        assertFalse(
            WatchlistManager.matchAndCheckCooldown(
                "Parus_major", watchedSet, cooldown5min, lastAlarmTimes, now
            )
        )
    }

    @Test
    fun `nach ablauf des cooldowns loest alarm wieder aus`() {
        val lastAlarmTimes = mapOf("Turdus_merula" to now - 6 * 60_000L)  // vor 6 Minuten
        assertTrue(
            WatchlistManager.matchAndCheckCooldown(
                "Turdus_merula", watchedSet, cooldown5min, lastAlarmTimes, now
            )
        )
    }

    @Test
    fun `cooldown 0 loest jeden treffer aus`() {
        val lastAlarmTimes = mapOf("Turdus_merula" to now - 10_000L)  // vor 10 Sekunden
        assertTrue(
            WatchlistManager.matchAndCheckCooldown(
                "Turdus_merula", watchedSet, cooldownMs = 0L, lastAlarmTimes, now
            )
        )
    }
}
