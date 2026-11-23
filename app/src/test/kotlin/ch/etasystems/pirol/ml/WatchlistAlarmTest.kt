package ch.etasystems.pirol.ml

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T244: Alarm-Knopf (dauerhaft / 24 h), reine Logik. */
class WatchlistAlarmTest {

    private val now = 1_000_000_000L

    @Test
    fun `24h alarm ist vor ablauf aktiv und danach nicht`() {
        val e = WatchlistEntry("Turdus_merula", expiresAtMs = now + WatchlistMatcher.TEMP_ALARM_MS)
        assertTrue(WatchlistMatcher.isActive(e, now))
        assertTrue(WatchlistMatcher.isActive(e, now + WatchlistMatcher.TEMP_ALARM_MS - 1))
        assertFalse(WatchlistMatcher.isActive(e, now + WatchlistMatcher.TEMP_ALARM_MS))
    }

    @Test
    fun `dauerhafter eintrag laeuft nie ab`() {
        assertTrue(WatchlistMatcher.isActive(WatchlistEntry("Turdus_merula"), Long.MAX_VALUE))
    }

    @Test
    fun `matcher ignoriert abgelaufene`() {
        val list = listOf(WatchlistEntry("Turdus_merula", expiresAtMs = now - 1))
        assertNull(WatchlistMatcher.findEntry(list, "Turdus merula", now))
        assertTrue(WatchlistMatcher.active(list, now).isEmpty())
    }

    @Test
    fun `hinzufuegen und entfernen`() {
        val added = WatchlistMatcher.withAlarm(emptyList(), "Parus major", "Kohlmeise", null)
        assertEquals("Parus_major", added.single().scientificName)
        assertNotNull(WatchlistMatcher.findEntry(added, "parus_major", now))
        assertTrue(WatchlistMatcher.withoutAlarm(added, "Parus major").isEmpty())
    }

    @Test
    fun `bestehender eintrag wird umgestellt statt dupliziert`() {
        val base = listOf(
            WatchlistEntry("Parus_major", priority = WatchlistPriority.high, expiresAtMs = now + 5)
        )
        val perm = WatchlistMatcher.withAlarm(base, "Parus major", "Kohlmeise", null)
        assertEquals(1, perm.size)
        assertNull(perm[0].expiresAtMs)
        assertEquals(WatchlistPriority.high, perm[0].priority)
    }

    @Test
    fun `json roundtrip alt und neu`() {
        val json = Json { ignoreUnknownKeys = true }
        val old = json.decodeFromString<Watchlist>(
            """{"species":[{"scientificName":"Turdus_merula","priority":"high"}]}"""
        )
        assertNull(old.species[0].expiresAtMs)
        val neu = Watchlist(species = listOf(WatchlistEntry("Parus_major", expiresAtMs = 42L)))
        val back = json.decodeFromString<Watchlist>(json.encodeToString(Watchlist.serializer(), neu))
        assertEquals(42L, back.species[0].expiresAtMs)
    }

    @Test
    fun `externe datei nur bei aenderung uebernehmen`() {
        val text = """{"species":[]}"""
        assertTrue(WatchlistMatcher.shouldAdoptExternal(text, null))
        assertFalse(WatchlistMatcher.shouldAdoptExternal(text, text.hashCode().toString()))
        assertTrue(WatchlistMatcher.shouldAdoptExternal(text + " ", text.hashCode().toString()))
    }
}
