package ch.etasystems.pirol.data.repository

import java.text.Normalizer

/**
 * T109: Hilfsfunktionen fuer die Session-Bemerkung (rein, ohne Android-Abhaengigkeiten).
 */
object SessionNoteUtil {

    /** Maximale Laenge der Kurzform im Dateinamen */
    const val MAX_FILE_NAME_PART = 40

    /**
     * Kurzform fuer Dateinamen: nur ASCII [A-Za-z0-9-], ae/oe/ue/ss-Transliteration,
     * uebrige Zeichen werden zu '-', mehrfache '-' zusammengefasst, max. [MAX_FILE_NAME_PART] Zeichen.
     * Leer wenn nichts Verwertbares uebrig bleibt.
     */
    fun sanitizeForFileName(note: String?): String {
        if (note.isNullOrBlank()) return ""
        val translit = note.trim()
            .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue")
            .replace("Ä", "Ae").replace("Ö", "Oe").replace("Ü", "Ue")
            .replace("ß", "ss")
        // Restliche Akzente (é, è, ...) entfernen
        val noAccents = Normalizer.normalize(translit, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
        return noAccents
            .replace(Regex("[^A-Za-z0-9]+"), "-")
            .trim('-')
            .take(MAX_FILE_NAME_PART)
            .trim('-')
    }
}
