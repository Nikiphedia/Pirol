package ch.etasystems.pirol.ui.analysis

/**
 * T202: Artsuche in Analyse (Session-Liste und Session-Detail).
 * Teilwort, gross/klein egal, gegen angezeigten Artnamen und wissenschaftlichen Namen
 * (Unterstrich und Leerzeichen gleichwertig).
 */
object SpeciesSearch {

    private fun norm(s: String) = s.trim().replace('_', ' ').lowercase()

    fun matches(query: String, commonName: String, scientificName: String): Boolean {
        val q = norm(query)
        if (q.isEmpty()) return true
        return norm(commonName).contains(q) || norm(scientificName).contains(q)
    }

    /** Anzahl Detektionen (je Eintrag in [scientificNames]) mit Treffer; [displayName] liefert den Anzeigenamen. */
    fun countMatches(query: String, scientificNames: List<String>, displayName: (String) -> String): Int {
        if (query.isBlank()) return scientificNames.size
        return scientificNames.count { matches(query, displayName(it), it) }
    }
}
