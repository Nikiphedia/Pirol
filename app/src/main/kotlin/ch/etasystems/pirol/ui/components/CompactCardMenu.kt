package ch.etasystems.pirol.ui.components

/**
 * T192: Eintraege des "+"-Menues der kompakten Artkarte.
 * Reine Logik (ohne Compose), damit das Inventar testbar ist: jede Aktion der Vollkarte,
 * die nicht in der Kompaktzeile steht (Bestaetigen/Ablehnen), muss hier auftauchen.
 */
enum class CompactMenuEntry {
    UNCERTAIN, CORRECT, DETAILS, PLAY_TOGGLE, ALTERNATIVES, REFERENCE, COMPARE, SHARE, LOCK
}

object CompactCardMenu {
    /** Reihenfolge = Anzeigereihenfolge (haeufigste Feldaktionen zuerst). */
    fun entries(
        hasMarkUncertain: Boolean,
        hasCorrect: Boolean,
        hasEditDetection: Boolean,
        hasPlay: Boolean,
        hasCandidates: Boolean,
        showReference: Boolean,
        hasCompare: Boolean,
        hasShare: Boolean,
        hasToggleLock: Boolean
    ): List<CompactMenuEntry> = buildList {
        if (hasPlay) add(CompactMenuEntry.PLAY_TOGGLE)
        if (hasMarkUncertain) add(CompactMenuEntry.UNCERTAIN)
        if (hasCorrect) add(CompactMenuEntry.CORRECT)
        if (hasEditDetection) add(CompactMenuEntry.DETAILS)
        if (hasCandidates) add(CompactMenuEntry.ALTERNATIVES)
        if (showReference) add(CompactMenuEntry.REFERENCE)
        if (hasCompare) add(CompactMenuEntry.COMPARE)
        if (hasShare) add(CompactMenuEntry.SHARE)
        if (hasToggleLock) add(CompactMenuEntry.LOCK)
    }
}
