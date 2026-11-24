package ch.etasystems.pirol.ui.help

/**
 * T266: Reine Logik des "?"-Knopfs (ohne Compose, unit-testbar).
 *
 * Erster Tipp schaltet den Hilfemodus sofort um (keine Wartezeit). Kommt der zweite Tipp innerhalb
 * von [windowMs], wird der Zustand von vor dem ersten Tipp wiederhergestellt und die Anleitung geoeffnet.
 * So verzoegert die Doppeltipp-Erkennung das einfache Antippen nicht.
 */
class HelpTapDecider(private val windowMs: Long = DOUBLE_TAP_WINDOW_MS) {

    data class Result(val active: Boolean, val openManual: Boolean)

    private var lastTapMs: Long? = null
    private var activeBeforeFirstTap = false

    fun onTap(nowMs: Long, activeNow: Boolean): Result {
        val last = lastTapMs
        if (last != null && nowMs - last in 0..windowMs) {
            lastTapMs = null
            return Result(active = activeBeforeFirstTap, openManual = true)
        }
        lastTapMs = nowMs
        activeBeforeFirstTap = activeNow
        return Result(active = !activeNow, openManual = false)
    }

    companion object {
        const val DOUBLE_TAP_WINDOW_MS = 300L
    }
}

/**
 * T266: Wahl der Anleitungsdatei in `assets/manual/`.
 * Quelle Deutsch = `BDA_PIROL.md`, Uebersetzungen `BDA_PIROL.<tag>.md` (z.B. `en`, `gsw-CH`).
 * Reihenfolge: exakter Tag, Basissprache, Deutsch.
 */
object ManualLanguage {
    const val DIR = "manual"
    const val GERMAN_FILE = "BDA_PIROL.md"

    fun fileFor(lang: String): String =
        if (lang.isEmpty() || lang.equals("de", ignoreCase = true)) GERMAN_FILE else "BDA_PIROL.$lang.md"

    /**
     * @param appTag App-Sprache wie [ch.etasystems.pirol.ui.AppLanguage.currentTag] ("" = System)
     * @param systemLanguage Sprache des Systems (nur bei appTag "")
     * @param available Dateinamen in `assets/manual/`
     */
    fun select(appTag: String, systemLanguage: String, available: Set<String>): String {
        val tag = appTag.ifEmpty { systemLanguage }.replace('_', '-')
        val candidates = buildList {
            if (tag.isNotEmpty()) {
                add(fileFor(tag))
                add(fileFor(tag.substringBefore('-').lowercase()))
            }
        }
        return candidates.firstOrNull { it in available } ?: GERMAN_FILE
    }
}

/**
 * T266: Abfang-Entscheidung des Hilfe-Modifiers (unit-testbar).
 * Im Hilfemodus wird jede Beruehrung eines Elements mit Hilfetext abgefangen; ausserhalb nie.
 */
object HelpIntercept {
    fun shouldIntercept(helpModeActive: Boolean, hasTopic: Boolean): Boolean = helpModeActive && hasTopic
}
