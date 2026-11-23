package ch.etasystems.pirol.ml

/**
 * Reine Logik fuer Watchlist-Abgleich und Alarm-Pause (T124).
 * Frei von Android-Abhaengigkeiten, direkt unit-testbar.
 */
object WatchlistMatcher {

    /**
     * Schluessel fuer den Vergleich: getrimmt, Leerzeichen -> Unterstrich, Kleinschreibung,
     * Synonyme auf den kanonischen Namen abgebildet (T105, [SpeciesKey]).
     */
    fun key(name: String): String = SpeciesKey.of(name)

    /** Findet den Watchlist-Eintrag zu einer Detektion (Leerzeichen/Unterstrich/Gross-Klein egal). */
    fun findEntry(
        entries: List<WatchlistEntry>,
        scientificName: String,
        now: Long = System.currentTimeMillis()
    ): WatchlistEntry? {
        val k = key(scientificName)
        return entries.firstOrNull { key(it.scientificName) == k && isActive(it, now) }
    }

    /** Dauer von "Alarm fuer 24 h" (T244) */
    const val TEMP_ALARM_MS: Long = 24L * 60 * 60 * 1000

    /** T244: Eintrag ist dauerhaft oder noch nicht abgelaufen. */
    fun isActive(entry: WatchlistEntry, now: Long): Boolean =
        entry.expiresAtMs?.let { now < it } ?: true

    /** T244: nur aktive Eintraege. */
    fun active(entries: List<WatchlistEntry>, now: Long): List<WatchlistEntry> =
        entries.filter { isActive(it, now) }

    /**
     * T244: Alarm fuer eine Art setzen (neu oder bestehenden Eintrag umstellen, Prioritaet/Notiz bleiben).
     * [expiresAtMs] null = dauerhaft.
     */
    fun withAlarm(
        entries: List<WatchlistEntry>,
        scientificName: String,
        commonName: String,
        expiresAtMs: Long?
    ): List<WatchlistEntry> {
        val k = key(scientificName)
        val existing = entries.firstOrNull { key(it.scientificName) == k }
        if (existing == null) {
            return entries + WatchlistEntry(
                scientificName = scientificName.trim().replace(' ', '_'),
                commonName = commonName,
                expiresAtMs = expiresAtMs
            )
        }
        return entries.map { if (it === existing) it.copy(expiresAtMs = expiresAtMs) else it }
    }

    /** T244: Eintrag zur Art entfernen. */
    fun withoutAlarm(entries: List<WatchlistEntry>, scientificName: String): List<WatchlistEntry> {
        val k = key(scientificName)
        return entries.filterNot { key(it.scientificName) == k }
    }

    /**
     * T244: Die externe watchlist.json wird nur uebernommen, wenn sie sich seit der letzten
     * Uebernahme geaendert hat; sonst gewinnen die Eintraege der App (sonst gingen
     * in der App gesetzte Alarme beim naechsten Laden verloren).
     */
    fun shouldAdoptExternal(externalText: String, lastAdoptedHash: String?): Boolean =
        externalText.hashCode().toString() != lastAdoptedHash

    /** true wenn die Pause seit dem letzten Alarm abgelaufen ist (cooldownMs <= 0: immer). */
    fun isCooldownOver(lastAlarmMs: Long?, cooldownMs: Long, now: Long): Boolean {
        if (cooldownMs <= 0L) return true
        val last = lastAlarmMs ?: return true
        return now - last >= cooldownMs
    }
}
