package ch.etasystems.pirol.ui

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * T120a: Sprache der App (Pro-App-Sprache) und Kopplung der Artnamen-Sprache.
 */
object AppLanguage {

    /** Tag "" = Systemsprache */
    const val SYSTEM_TAG = ""

    /** Anzeigenamen jeweils in der eigenen Sprache (Tag, Name); Systemsprache wird separat beschriftet */
    val OPTIONS: List<Pair<String, String>> = listOf(
        "de" to "Deutsch",
        "en" to "English",
        "fr" to "Français",
        "it" to "Italiano",
        "ca" to "Català",
        "pt" to "Português",
        "nl" to "Nederlands",
        "gsw-CH" to "Bärndütsch"
    )

    /** Sprache des Systems (unabhaengig von der Pro-App-Sprache) */
    fun systemLanguage(): String =
        android.content.res.Resources.getSystem().configuration.locales[0].language

    /** Aktuell gesetzter Tag ("" = Systemsprache) */
    fun currentTag(): String {
        val locales = AppCompatDelegate.getApplicationLocales()
        if (locales.isEmpty) return SYSTEM_TAG
        return normalizeTag(locales.toLanguageTags().substringBefore(','))
    }

    /** Setzt die App-Sprache ("" = Systemsprache); AppCompat startet die Activity neu */
    fun apply(tag: String) {
        val list = if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList()
        else LocaleListCompat.forLanguageTags(tag)
        AppCompatDelegate.setApplicationLocales(list)
    }

    /** Reduziert Tags wie "de-CH" auf einen Eintrag aus OPTIONS (gsw-CH bleibt) */
    fun normalizeTag(tag: String): String {
        if (tag.isEmpty()) return SYSTEM_TAG
        if (tag.startsWith("gsw")) return "gsw-CH"
        return tag.substringBefore('-').substringBefore('_').lowercase()
    }

    /**
     * Sprachcode fuer Artnamen aus dem App-Sprach-Tag. Mundart (gsw) hat keine eigene
     * Namensliste und faellt auf Deutsch zurueck; "" (System) nutzt [systemLanguage].
     */
    fun speciesLanguageForTag(tag: String, systemLanguage: String): String {
        val base = if (tag.isEmpty()) systemLanguage.lowercase() else normalizeTag(tag)
        return when {
            base.startsWith("gsw") -> "de"
            base.isEmpty() -> "en"
            else -> base
        }
    }

    /**
     * Wirksame Artnamen-Sprache: explizit gewaehlte Sprache hat Vorrang, sonst folgt sie der App-Sprache.
     */
    fun effectiveSpeciesLanguage(
        explicit: Boolean,
        storedSpeciesLanguage: String,
        appTag: String,
        systemLanguage: String
    ): String =
        if (explicit) storedSpeciesLanguage else speciesLanguageForTag(appTag, systemLanguage)
}
