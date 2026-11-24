package ch.etasystems.pirol.data.repository

import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/** T258: Eine Datei einer Session fuer Export (ZIP oder Ordner); [path] relativ zum Session-Ordner mit '/'. */
class ExportEntry(val path: String, val open: () -> InputStream)

/**
 * T258: Gemeinsame Regeln fuer Upload-ZIP und "Speichern unter" (ZIP/Ordner).
 * Nicht exportiert werden Schattendateien (T240), die Zusatzdatei der Detektions-Updates (T253)
 * und die Auftragsdatei des Bearbeitens (T257). detections.jsonl kommt aus dem zusammengefuehrten Stand.
 * T261: auch nicht die Merkliste der Mensch-Fenster (verriete, wo Menschen zu hoeren waren).
 */
object SessionExportRules {

    const val DETECTIONS_ENTRY = "detections.jsonl"

    /** true = Datei mit diesem Namen gehoert nicht in den Export. */
    fun isExcluded(fileName: String): Boolean =
        ShadowReplace.isShadowFile(fileName) ||
            fileName == DetectionUpdateLog.FILE ||
            fileName == SessionInfoEdit.JOURNAL_FILE ||
            fileName == ch.etasystems.pirol.audio.export.HumanWindows.FILE

    /** Alle exportierbaren Dateien eines Session-Ordners im Dateisystem (rekursiv). */
    fun entriesOf(sessionDir: File): List<ExportEntry> =
        sessionDir.walkTopDown()
            .filter { it.isFile && !isExcluded(it.name) }
            .map { f -> ExportEntry(f.relativeTo(sessionDir).path.replace('\\', '/')) { FileInputStream(f) } }
            .toList()
}
