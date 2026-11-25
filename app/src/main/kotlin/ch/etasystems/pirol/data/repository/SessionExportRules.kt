package ch.etasystems.pirol.data.repository

import ch.etasystems.pirol.ml.DetectionResult
import ch.etasystems.pirol.rescan.RescanFiles
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/** T258: Eine Datei einer Session fuer Export (ZIP oder Ordner); [path] relativ zum Session-Ordner mit '/'. */
class ExportEntry(val path: String, val open: () -> InputStream)

/**
 * T258: Gemeinsame Regeln fuer Upload-ZIP und "Speichern unter" (ZIP/Ordner).
 * Nicht exportiert werden Schattendateien (T240), die Zusatzdatei der Detektions-Updates (T253)
 * und die Auftragsdatei des Bearbeitens (T257). detections.jsonl kommt aus dem zusammengefuehrten Stand.
 * T261: auch nicht die Merkliste der Mensch-Fenster (verriete, wo Menschen zu hoeren waren).
 * T303: unter memos/ nur WAVs, die noch zu einem Memo gehoeren (memos.jsonl oder voiceMemoFile einer
 * Detektion) und nicht gerade zum Loeschen vorgemerkt sind ([PendingMemoDeletes]).
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
        keepReferencedMemos(
            sessionDir.walkTopDown()
                .filter { it.isFile && !isExcluded(it.name) }
                .map { f -> ExportEntry(f.relativeTo(sessionDir).path.replace('\\', '/')) { FileInputStream(f) } }
                .toList(),
            sessionDir.name
        )

    private fun isMemoEntry(path: String): Boolean = path.startsWith(SessionMemos.DIR + "/")

    /**
     * T303: Memo-WAVs ohne Bezug weglassen (verwaiste Datei nach gescheitertem Loeschen, Memo in der Undo-Frist).
     * Bezug: Eintrag in memos.jsonl (Session-Memo) oder voiceMemoFile der aktuellen Fassung einer Detektion
     * (Original und Varianten, Art-Memo). Gelesen wird ueber die Eintraege selbst (File und SAF gleich).
     * [sessionName] = Ordnername (Schluessel fuer [PendingMemoDeletes]).
     */
    fun keepReferencedMemos(entries: List<ExportEntry>, sessionName: String): List<ExportEntry> {
        if (entries.none { isMemoEntry(it.path) }) return entries
        val byPath = entries.associateBy { it.path }
        fun text(path: String): String? = byPath[path]?.let { e -> e.open().use { it.readBytes().toString(Charsets.UTF_8) } }
        val detectionTexts = entries.map { it.path }
            .filter { '/' !in it && RescanFiles.updatesFor(it) != null }
            .mapNotNull { name ->
                val main = text(name) ?: return@mapNotNull null
                val updates = DetectionUpdateLog.latestLines(text(RescanFiles.updatesFor(name)!!))
                if (updates.isEmpty()) main else DetectionUpdateLog.applyTo(main, updates).text
            }
        val referenced = referencedMemos(text(SessionMemos.FILE), detectionTexts) - PendingMemoDeletes.of(sessionName)
        return entries.filter { !isMemoEntry(it.path) || it.path in referenced }
    }

    /** T303: referenzierte Memo-Pfade (relativ, mit '/') aus memos.jsonl und Detektionstexten (letzte Fassung je ID). */
    fun referencedMemos(memosJsonl: String?, detectionTexts: List<String>): Set<String> {
        val out = HashSet<String>()
        SessionMemos.parse(memosJsonl).forEach { out += normalize(it.file) }
        for (t in detectionTexts) {
            val last = LinkedHashMap<String, String?>()
            t.lineSequence().filter { it.isNotBlank() }.forEach { line ->
                val d = try { SessionJson.lenient.decodeFromString<DetectionResult>(line) } catch (_: Exception) { null }
                if (d != null) last[d.id] = d.voiceMemoFile
            }
            last.values.filterNotNull().filter { it.isNotBlank() }.forEach { out += normalize(it) }
        }
        return out
    }

    private fun normalize(p: String): String = p.trim().replace('\\', '/').removePrefix("./")
}

/**
 * T303: Session-Memos in der Undo-Frist des Loeschens (prozessweit, nur Speicher). Ein Export in dieser Frist
 * laesst sie bereits weg; Undo nimmt die Vormerkung zurueck. Nach einem Prozess-Ende ist nichts vorgemerkt
 * (das Memo wurde dann nie geloescht und ist wieder sichtbar).
 */
object PendingMemoDeletes {
    private val pending = ConcurrentHashMap.newKeySet<String>()

    private fun key(sessionName: String, file: String) = "$sessionName|$file"

    fun add(sessionName: String, file: String) { pending += key(sessionName, file) }
    fun remove(sessionName: String, file: String) { pending -= key(sessionName, file) }

    /** Vorgemerkte Memo-Pfade der Session [sessionName] */
    fun of(sessionName: String): Set<String> {
        val prefix = "$sessionName|"
        return pending.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }.toSet()
    }
}
