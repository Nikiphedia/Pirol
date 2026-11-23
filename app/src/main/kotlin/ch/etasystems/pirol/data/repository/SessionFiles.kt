package ch.etasystems.pirol.data.repository

import ch.etasystems.pirol.audio.export.FileSegment
import ch.etasystems.pirol.audio.export.SegmentSource
import ch.etasystems.pirol.audio.export.SegmentedRecording
import ch.etasystems.pirol.ml.DetectionResult
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * T131: Zugriff auf die Dateien EINER Session, unabhaengig vom Speichermodus
 * (File = [FileSessionFiles], SAF = DocSessionFiles). Alle Aufrufe blockierend (IO-Dispatcher);
 * Serialisierung gegen parallele Schreiber macht der Aufrufer ([SessionManager] fileMutex).
 */
interface SessionFiles {
    /** Session-Ordnername (= Session-ID) */
    val name: String
    fun exists(fileName: String): Boolean
    /** Inhalt als UTF-8-Text; null = Datei fehlt */
    fun readText(fileName: String): String?
    /** Ersetzt (oder erstellt) die Datei vollstaendig */
    fun writeReplacing(fileName: String, content: String)
    /** Haengt [content] an (Datei wird bei Bedarf erstellt) */
    fun append(fileName: String, content: String)
    /** Dateinamen direkt im Session-Ordner */
    fun listNames(): List<String>
    /** WAV-Segmente in Aufnahme-Reihenfolge (leer = keine Aufnahme) */
    fun segmentSources(): List<SegmentSource>
    /** Ganzen Session-Ordner loeschen */
    fun deleteAll(): Boolean
    /** T221: einzelne Datei loeschen (true = geloescht oder nicht vorhanden) */
    fun delete(fileName: String): Boolean

    /** Aufnahme (alle Segmente) oder null */
    fun recording(): SegmentedRecording? =
        SegmentedRecording.fromSources(segmentSources())
}

/** File-Modus: Session-Ordner im Dateisystem. */
class FileSessionFiles(val dir: File) : SessionFiles {
    override val name: String get() = dir.name
    override fun exists(fileName: String) = File(dir, fileName).exists()
    override fun readText(fileName: String): String? =
        File(dir, fileName).takeIf { it.exists() }?.readText()
    override fun writeReplacing(fileName: String, content: String) =
        SessionManager.writeFileReplacing(File(dir, fileName), content)
    override fun append(fileName: String, content: String) {
        File(dir, fileName).appendText(content)
    }
    override fun listNames(): List<String> = dir.list()?.toList() ?: emptyList()
    override fun segmentSources(): List<SegmentSource> =
        SegmentedRecording.segmentFiles(dir).map { FileSegment(it) }
    override fun deleteAll(): Boolean = dir.deleteRecursively()
    override fun delete(fileName: String): Boolean = File(dir, fileName).let { !it.exists() || it.delete() }
}

/** Reine Hilfsfunktionen (Pfad-/Namenslogik, JVM-testbar). */
object SessionFilesLogic {
    private val json = Json { prettyPrint = false }
    private val jsonLenient = Json { ignoreUnknownKeys = true }

    /** Wurzel fuer synthetische Session-Schluessel von SAF-Sessions (nur Identitaet, nie Dateizugriff). */
    const val SAF_KEY_ROOT = "/saf"

    /** Synthetischer File-Schluessel fuer eine SAF-Session: /saf/[Tag/]{sessionId} */
    fun safKey(daySubdir: String?, sessionName: String): File =
        if (daySubdir.isNullOrEmpty()) File(SAF_KEY_ROOT, sessionName)
        else File(File(SAF_KEY_ROOT, daySubdir), sessionName)

    /**
     * Ist der Kandidat die gerade aktive Session? File-Sessions ueber den Pfad, SAF-Sessions
     * (Pfad nicht vergleichbar) ueber den Ordnernamen — die Session-ID ist eindeutig.
     */
    fun isSameSession(
        activeDirPath: String?,
        activeDocName: String?,
        candidatePath: String,
        candidateName: String
    ): Boolean =
        (activeDirPath != null && activeDirPath == candidatePath) ||
            (activeDocName != null && activeDocName == candidateName)

    /** Ersetzt die Zeile der Detektion mit gleicher ID in JSONL-Text; unlesbare Zeilen bleiben unveraendert. */
    fun replaceDetectionInText(text: String, updated: DetectionResult): String =
        replaceDetectionsInText(text, mapOf(updated.id to updated)).text

    /** T236: Ergebnis eines gebuendelten Ersetzens */
    data class ReplaceResult(val text: String, val matchedIds: Set<String>)

    /**
     * T236: Ersetzt alle Zeilen, deren ID in [updates] vorkommt, in einem Durchgang (ein Rewrite fuer n Updates).
     * Die ID wird per Praefix `{"id":"…"` gelesen (so schreibt kotlinx die Zeilen); nur abweichende Zeilen
     * werden voll dekodiert. Ersetzte Zeilen = [json].encodeToString (Format unveraendert).
     */
    fun replaceDetectionsInText(text: String, updates: Map<String, DetectionResult>): ReplaceResult {
        if (updates.isEmpty()) return ReplaceResult(text, emptySet())
        val lines = text.lines().let { if (it.lastOrNull() == "") it.dropLast(1) else it }
        val matched = mutableSetOf<String>()
        val replaced = lines.map { raw ->
            if (raw.isBlank()) return@map raw
            val id = detectionIdOf(raw) ?: return@map raw
            val upd = updates[id] ?: return@map raw
            matched += id
            json.encodeToString(upd)
        }
        val out = if (replaced.isEmpty()) "" else replaced.joinToString("\n") + "\n"
        return ReplaceResult(out, matched)
    }

    private const val ID_PREFIX = "{\"id\":\""

    /** T236: ID einer JSONL-Zeile (Praefix, sonst Decoder); null = unlesbar */
    fun detectionIdOf(line: String): String? {
        if (line.startsWith(ID_PREFIX)) {
            val end = line.indexOf('"', ID_PREFIX.length)
            // Escape in der ID (bei UUIDs nie) -> Decoder entscheidet
            if (end > ID_PREFIX.length && line.lastIndexOf('\\', end) < ID_PREFIX.length) {
                return line.substring(ID_PREFIX.length, end)
            }
        }
        return try { jsonLenient.decodeFromString<DetectionResult>(line).id } catch (_: Exception) { null }
    }

    /** Entfernt File-Sessions, deren Ordnername schon als SAF-Session gelistet ist (Duplikate). */
    fun dropListedNames(fileSessions: List<File>, safNames: Set<String>): List<File> =
        fileSessions.filter { it.name !in safNames }
}
