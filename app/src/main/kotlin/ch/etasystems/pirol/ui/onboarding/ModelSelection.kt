package ch.etasystems.pirol.ui.onboarding

import ch.etasystems.pirol.ml.ModelProfile

/** T145: Hilfsfunktionen fuer die Mehrfachwahl der Modelle im Onboarding (ohne Android-Abhaengigkeit). */
object ModelSelection {

    /** Artenzahl mit Tausender-Apostroph, z.B. 11725 -> "11'725" */
    fun formatSpeciesCount(count: Int): String {
        val digits = count.toString()
        val sb = StringBuilder()
        digits.forEachIndexed { i, c ->
            if (i > 0 && (digits.length - i) % 3 == 0) sb.append('\'')
            sb.append(c)
        }
        return sb.toString()
    }

    /** Download-Reihenfolge: Katalogreihenfolge, nur gewaehlte Modelle */
    fun queue(selected: Set<String>, catalog: List<ModelProfile>): List<ModelProfile> =
        catalog.filter { it.id in selected && it.downloadUrl != null }

    /** Aktives Modell nach dem Download: V3.0 final falls erfolgreich, sonst das erste erfolgreiche */
    fun pickActive(succeeded: List<ModelProfile>, preferredId: String): ModelProfile? =
        succeeded.firstOrNull { it.id == preferredId } ?: succeeded.firstOrNull()
}
