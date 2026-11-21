package ch.etasystems.pirol.data.repository

/**
 * T180: Reine Logik fuer Mehrfachauswahl, Namenskonflikte und Ergebnis-Zusammenfassung
 * (ohne Android-Abhaengigkeit, per Unit-Test pruefbar).
 */
object SessionBatchLogic {

    /** Auswahl umschalten: enthalten -> entfernen, sonst hinzufuegen. */
    fun toggle(selection: Set<String>, id: String): Set<String> =
        if (id in selection) selection - id else selection + id

    /** Alle waehlbaren IDs (laufende Session ausgenommen). */
    fun selectAll(selectableIds: List<String>): Set<String> = selectableIds.toSet()

    /** Auswahl auf noch vorhandene, waehlbare IDs einschraenken (nach Neuladen der Liste). */
    fun prune(selection: Set<String>, selectableIds: Collection<String>): Set<String> =
        selection.intersect(selectableIds.toSet())

    /** Eindeutiger Zielname: bei Konflikt Suffix "-2", "-3", ... (Vergleich ohne Gross/Klein). */
    fun uniqueName(base: String, existing: Collection<String>): String {
        val taken = existing.map { it.lowercase() }.toSet()
        if (base.lowercase() !in taken) return base
        var n = 2
        while ("$base-$n".lowercase() in taken) n++
        return "$base-$n"
    }
}

/** Ergebnis einer Stapel-Aktion (Loeschen oder Verschieben). */
data class BatchResult(
    val succeeded: Int,
    val failed: Int,
    val failedNames: List<String> = emptyList(),
    val cancelled: Boolean = false
) {
    val total: Int get() = succeeded + failed
}

/** Fortschritt einer Verschiebung. */
data class MoveProgress(
    val sessionIndex: Int,
    val sessionCount: Int,
    val currentName: String,
    val bytesDone: Long,
    val bytesTotal: Long,
    val filesDone: Int,
    val filesTotal: Int
) {
    /** Anteil 0..1 ueber Bytes (Fallback: Dateien). */
    val fraction: Float
        get() = when {
            bytesTotal > 0 -> (bytesDone.toFloat() / bytesTotal).coerceIn(0f, 1f)
            filesTotal > 0 -> (filesDone.toFloat() / filesTotal).coerceIn(0f, 1f)
            else -> 0f
        }
}
