package ch.etasystems.pirol.ui.map

import ch.etasystems.pirol.ml.SpeciesKey

/**
 * T204: Artfilter der Karte als reine Funktionen.
 * Gespeichert wird die Menge der ausgeblendeten Arten (Schluessel), damit neue Arten
 * (z.B. live hinzukommend) standardmaessig sichtbar sind.
 */
object MapSpeciesFilter {

    /** Kanonischer Artschluessel eines Markers (T105); ohne wissenschaftlichen Namen der Anzeigename. */
    fun keyOf(marker: MapViewModel.MapMarker): String =
        SpeciesKey.of(marker.scientificName.ifBlank { marker.species })

    /**
     * @param hidden Schluessel der ausgeblendeten Arten
     * @param onlyAlarm true = nur Arten der Watchlist (Schluessel in [watched])
     */
    fun filter(
        markers: List<MapViewModel.MapMarker>,
        hidden: Set<String>,
        onlyAlarm: Boolean,
        watched: Set<String>
    ): List<MapViewModel.MapMarker> {
        if (hidden.isEmpty() && !onlyAlarm) return markers
        return markers.filter { m ->
            val key = keyOf(m)
            key !in hidden && (!onlyAlarm || key in watched)
        }
    }

    /** Eintrag der Artenliste im Filterdialog. */
    data class SpeciesEntry(val key: String, val scientificName: String, val fallbackName: String, val count: Int)

    /** Arten der gegebenen Marker mit Anzahl, absteigend nach Anzahl, dann nach Schluessel. */
    fun speciesEntries(markers: List<MapViewModel.MapMarker>): List<SpeciesEntry> =
        markers.groupBy { keyOf(it) }
            .map { (key, list) ->
                val first = list.first()
                SpeciesEntry(key, first.scientificName, first.species, list.size)
            }
            .sortedWith(compareByDescending<SpeciesEntry> { it.count }.thenBy { it.key })

    /** Hidden-Menge nach Umschalten einer Art. */
    fun toggle(hidden: Set<String>, key: String): Set<String> =
        if (key in hidden) hidden - key else hidden + key
}
