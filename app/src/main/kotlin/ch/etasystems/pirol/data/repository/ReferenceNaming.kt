package ch.etasystems.pirol.data.repository

/**
 * T123: Reine Hilfsfunktionen fuer Referenz-Dateinamen und Artnamen (unit-testbar).
 */
object ReferenceNaming {

    /** "Turdus merula" / " Turdus  merula " -> "Turdus_merula" (Unterstrich-Format, ASCII-sicher). */
    fun speciesKey(name: String): String =
        name.trim().replace(Regex("[^A-Za-z0-9]+"), "_").trim('_').ifEmpty { "Unbekannt" }

    /**
     * Naechster freier Dateiname `ref_NNN_{Art}.wav`; zaehlt hoch, bis kein Konflikt mit [existing] besteht.
     */
    fun nextWavFileName(speciesKey: String, existing: Collection<String>): String {
        var n = existing.size + 1
        while (true) {
            val candidate = "ref_${String.format(java.util.Locale.ROOT, "%03d", n)}_$speciesKey.wav"
            if (candidate !in existing) return candidate
            n++
        }
    }

    /** Dateiendung (klein, ohne Punkt) aus einem Anzeigenamen; leer falls keine. */
    fun extensionOf(displayName: String?): String =
        displayName?.substringAfterLast('.', "")?.lowercase().orEmpty()

    /** Unterstuetzte Import-Endungen. */
    val IMPORT_EXTENSIONS = setOf("wav", "flac", "mp3", "m4a", "aac", "ogg")
}
