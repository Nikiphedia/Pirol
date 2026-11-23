package ch.etasystems.pirol.ml

/** T190: Uebernahme einer Artenliste in die Watchlist (Spec Abschnitt 8). Reine Logik, testbar. */
object WatchlistFromList {

    /**
     * @param merge true = zusammenfuehren (vorhandene Arten behalten Prioritaet und Notiz, nichts wird entfernt),
     *              false = ersetzen (Watchlist enthaelt danach genau die Listenarten mit Prioritaet normal)
     * @param commonName Anzeigename pro Art (Resolver, sonst species_info.common_name, sonst "")
     * @param nowIso Zeitpunkt der Uebernahme, ISO 8601 mit Offset
     */
    fun apply(
        current: Watchlist,
        list: CustomSpeciesList,
        merge: Boolean,
        commonName: (String) -> String,
        nowIso: String
    ): Watchlist {
        val base = if (merge) current.species else emptyList()
        val known = base.map { SpeciesKey.of(it.scientificName) }.toHashSet()
        val added = mutableListOf<WatchlistEntry>()
        for (name in list.species.sorted()) {
            if (!known.add(SpeciesKey.of(name))) continue
            added.add(WatchlistEntry(scientificName = name, commonName = commonName(name)))
        }
        // Name: bestehender bleibt beim Zusammenfuehren in eine nicht leere Watchlist, sonst Listenname
        val name = if (merge && current.species.isNotEmpty()) current.name else list.nameDe
        return current.copy(name = name, updatedAt = nowIso, species = base + added)
    }
}
