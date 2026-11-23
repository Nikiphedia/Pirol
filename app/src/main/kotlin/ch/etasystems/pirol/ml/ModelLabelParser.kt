package ch.etasystems.pirol.ml

/**
 * T103a: Parser fuer die Label-Dateien der Modellprofile (ohne Android-Abhaengigkeit, unit-testbar).
 * Die Reihenfolge der Zeilen entspricht dem Index des Score-Ausgangs.
 */
object ModelLabelParser {

    /** Ein Label: wissenschaftlicher Name (mit Leerzeichen, wie in der Datei), Trivialname, Namen je Sprache */
    data class Label(
        val scientificName: String,
        val commonName: String,
        val namesByLanguage: Map<String, String> = emptyMap()
    )

    /** @param languages nur diese Sprachcodes uebernehmen (null = alle) — spart Speicher bei 36 Sprachen */
    fun parse(
        text: String,
        format: ModelProfile.LabelFormat,
        languages: Set<String>? = null,
        generic: ModelProfile.GenericLabels? = null
    ): List<Label> =
        when (format) {
            ModelProfile.LabelFormat.PREVIEW_SEMICOLON -> parsePreview(text)
            ModelProfile.LabelFormat.FINAL_CSV -> parseFinal(text, languages)
            ModelProfile.LabelFormat.GENERIC_CSV ->
                parseGeneric(text, requireNotNull(generic) { "Label-Beschreibung fehlt" })
        }

    /**
     * T151a: Generische Label-CSV laut Profil-JSON `pirol-model/1`. Zeile n (nach der Kopfzeile)
     * = Klasse n. Leere Zeilen werden uebersprungen; eine Zeile ohne wissenschaftlichen Namen ist
     * ein Fehler (sonst verschiebt sich die Zuordnung Index → Art).
     * Unterstriche im wissenschaftlichen Namen werden wie in den BirdNET-Labels zu Leerzeichen.
     * Ohne Trivialnamen-Spalte gilt der wissenschaftliche Name.
     *
     * @throws IllegalArgumentException bei fehlender Spalte oder leerem wissenschaftlichem Namen
     */
    fun parseGeneric(text: String, spec: ModelProfile.GenericLabels): List<Label> {
        val lines = text.trimStart('\uFEFF').lineSequence().filter { it.isNotBlank() }.iterator()
        if (!lines.hasNext()) return emptyList()
        val header = if (spec.header) splitCsvLine(lines.next(), spec.delimiter).map { it.trim() } else null

        fun resolve(col: ModelProfile.Column): Int {
            col.index?.let { return it }
            val name = col.name ?: throw IllegalArgumentException("Spaltenangabe leer")
            require(header != null) { "Spaltenname '$name' ohne Kopfzeile" }
            val idx = header.indexOf(name)
            require(idx >= 0) { "Spalte '$name' fehlt" }
            return idx
        }
        val sciCol = resolve(spec.scientificColumn)
        val comCol = spec.commonColumn?.let { resolve(it) }

        val out = mutableListOf<Label>()
        for (line in lines) {
            val f = splitCsvLine(line, spec.delimiter)
            val sci = f.getOrElse(sciCol) { "" }.trim().replace('_', ' ')
            require(sci.isNotEmpty()) { "Zeile ${out.size + 1}: kein wissenschaftlicher Name" }
            val com = comCol?.let { f.getOrElse(it) { "" }.trim() }.orEmpty().ifEmpty { sci }
            out += Label(sci, com)
        }
        return out
    }

    /**
     * Preview 3/3.1: Semikolon-CSV mit Header `idx;id;sci_name;com_name;class;order` (wie vor T103a).
     * BOM wird entfernt; Zeilen ohne wissenschaftlichen Namen werden verworfen.
     */
    fun parsePreview(text: String): List<Label> =
        text.lineSequence()
            .drop(1)
            .filter { it.isNotBlank() }
            .map { line ->
                val parts = line.trimStart('\uFEFF').trim().split(";")
                Label(parts.getOrElse(2) { "" }.trim(), parts.getOrElse(3) { "" }.trim())
            }
            .filter { it.scientificName.isNotEmpty() }
            .toList()

    /**
     * 3.0 final: Komma-CSV (RFC 4180, Felder koennen in Anfuehrungszeichen stehen) mit Kopfzeile
     * `ix,birdnet_id,scientific_name,common_name,taxon_group,inat_id,ebird_code,common_name_en,...`.
     * Sprachspalten `common_name_<lang>` werden als Sprachcode `<lang>` uebernommen (z.B. "de", "pt_PT").
     * Die Spalte `ix` bestimmt die Position; fehlt sie, gilt die Zeilenreihenfolge.
     */
    fun parseFinal(text: String, languages: Set<String>? = null): List<Label> {
        val lines = text.trimStart('\uFEFF').lineSequence().filter { it.isNotBlank() }.iterator()
        if (!lines.hasNext()) return emptyList()
        val header = splitCsvLine(lines.next()).map { it.trim() }
        val ixCol = header.indexOf("ix")
        val sciCol = header.indexOf("scientific_name")
        val comCol = header.indexOf("common_name")
        require(sciCol >= 0) { "Spalte scientific_name fehlt" }
        val langCols = header.withIndex()
            .filter { it.value.startsWith("common_name_") }
            .map { it.index to it.value.removePrefix("common_name_") }
            .filter { languages == null || it.second in languages }

        val indexed = mutableListOf<Pair<Int, Label>>()
        var row = 0
        for (line in lines) {
            val f = splitCsvLine(line)
            val sci = f.getOrElse(sciCol) { "" }.trim()
            val ix = if (ixCol >= 0) f.getOrNull(ixCol)?.trim()?.toIntOrNull() ?: row else row
            row++
            if (sci.isEmpty()) continue
            val names = HashMap<String, String>(langCols.size)
            for ((col, lang) in langCols) {
                val n = f.getOrElse(col) { "" }.trim()
                if (n.isNotEmpty()) names[lang] = n
            }
            val com = f.getOrElse(comCol) { "" }.trim().ifEmpty { names["en"] ?: sci }
            indexed += ix to Label(sci, com, names)
        }
        return indexed.sortedBy { it.first }.map { it.second }
    }

    /** Eine CSV-Zeile nach RFC 4180 zerlegen (Trennzeichen, doppelte Anfuehrungszeichen als Escape) */
    internal fun splitCsvLine(line: String, delimiter: Char = ','): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inQuotes && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                c == '"' -> inQuotes = !inQuotes
                c == delimiter && !inQuotes -> { out += sb.toString(); sb.clear() }
                c == '\r' && !inQuotes -> {}
                else -> sb.append(c)
            }
            i++
        }
        out += sb.toString()
        return out
    }
}
