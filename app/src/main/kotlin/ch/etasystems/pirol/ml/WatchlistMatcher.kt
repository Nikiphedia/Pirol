package ch.etasystems.pirol.ml

/**
 * Reine Logik fuer Watchlist-Abgleich und Alarm-Pause (T124).
 * Frei von Android-Abhaengigkeiten, direkt unit-testbar.
 */
object WatchlistMatcher {

    /** Schluessel fuer den Vergleich: getrimmt, Leerzeichen -> Unterstrich, Kleinschreibung. */
    fun key(name: String): String = name.trim().replace(' ', '_').lowercase()

    /** Findet den Watchlist-Eintrag zu einer Detektion (Leerzeichen/Unterstrich/Gross-Klein egal). */
    fun findEntry(entries: List<WatchlistEntry>, scientificName: String): WatchlistEntry? {
        val k = key(scientificName)
        return entries.firstOrNull { key(it.scientificName) == k }
    }

    /** true wenn die Pause seit dem letzten Alarm abgelaufen ist (cooldownMs <= 0: immer). */
    fun isCooldownOver(lastAlarmMs: Long?, cooldownMs: Long, now: Long): Boolean {
        if (cooldownMs <= 0L) return true
        val last = lastAlarmMs ?: return true
        return now - last >= cooldownMs
    }
}
