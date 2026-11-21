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

/**
 * T181: Prueft ein Verschiebe-Ziel gegen die PIROL-Basis und die Quell-Sessions.
 * Alle Angaben sind SAF-Dokument-IDs (z.B. `primary:Download/PIROL/2026-11-20`), Vergleich
 * ohne Gross/Klein und ohne abschliessenden Schraegstrich. Reine Logik, ohne Android-Abhaengigkeit.
 */
object MoveTargetGuard {

    enum class Verdict {
        OK,
        /** Ziel liegt in oder ist die PIROL-Basis: wuerde die Session nur umhaengen/doppeln. */
        INSIDE_BASE,
        /** Ziel ist Quell-Session, liegt darin oder ist ihr Elternordner. */
        SAME_AS_SOURCE
    }

    private fun norm(id: String) = id.trim().trimEnd('/').lowercase()

    private fun isSameOrInside(child: String, parent: String): Boolean {
        if (child == parent) return true
        // Wurzel-ID ("primary:") hat keinen Pfadanteil: alles darunter beginnt direkt nach dem ':'
        return if (parent.endsWith(":")) child.startsWith(parent) else child.startsWith("$parent/")
    }

    private fun parentOf(id: String): String? =
        id.lastIndexOf('/').takeIf { it > 0 }?.let { id.substring(0, it) }

    fun check(targetDocId: String, baseDocIds: Collection<String>, sourceDocIds: Collection<String>): Verdict {
        val target = norm(targetDocId)
        for (src in sourceDocIds.map(::norm).filter { it.isNotEmpty() }) {
            if (isSameOrInside(target, src) || target == parentOf(src)) return Verdict.SAME_AS_SOURCE
        }
        for (base in baseDocIds.map(::norm).filter { it.isNotEmpty() }) {
            // Eine reine Speicher-Wurzel als Basis wuerde jedes Ziel sperren: dann nur exakte Gleichheit
            val rootOnly = base.endsWith(":")
            if (if (rootOnly) target == base else isSameOrInside(target, base)) return Verdict.INSIDE_BASE
        }
        return Verdict.OK
    }
}
