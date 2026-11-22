package ch.etasystems.pirol.ui.analysis

/**
 * T194: Geparste Raven Selection Table (TSV) fuer die Tabellenanzeige.
 * Spalten und Zellen bleiben wie in der Datei; [numericColumns] markiert rein numerische Spalten
 * (rechtsbuendige Darstellung).
 */
data class RavenTable(
    val header: List<String>,
    val rows: List<List<String>>,
    val numericColumns: Set<Int>
) {
    companion object {
        private val NUMBER = Regex("""-?\d+([.,]\d+)?([eE][-+]?\d+)?""")

        /** Zerlegt TSV-Text (Tabs, leere Felder bleiben erhalten); null bei leerem Text. */
        fun parse(text: String): RavenTable? {
            val lines = text.removePrefix("\uFEFF").split('\n').map { it.trimEnd('\r') }
                .dropLastWhile { it.isEmpty() }
            if (lines.isEmpty()) return null
            val header = lines.first().split('\t')
            val cols = header.size
            val rows = lines.drop(1).map { line ->
                val cells = line.split('\t')
                // Zeilen auf Kopfbreite bringen (fehlende Felder leer, ueberzaehlige beibehalten)
                if (cells.size < cols) cells + List(cols - cells.size) { "" } else cells
            }
            val numeric = (0 until cols).filter { c ->
                val values = rows.mapNotNull { it.getOrNull(c) }.filter { it.isNotEmpty() }
                values.isNotEmpty() && values.all { NUMBER.matches(it) }
            }.toSet()
            return RavenTable(header, rows, numeric)
        }

        /** Spaltenbreite in dp nach laengstem Inhalt (ca. 7 dp pro Zeichen), begrenzt auf 64..240. */
        fun columnWidthsDp(table: RavenTable): List<Int> =
            table.header.indices.map { c ->
                val longest = maxOf(
                    table.header[c].length,
                    table.rows.maxOfOrNull { it.getOrNull(c)?.length ?: 0 } ?: 0
                )
                (longest * 7 + 24).coerceIn(64, 240)
            }
    }
}
