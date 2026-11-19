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
    fun replaceDetectionInText(text: String, updated: DetectionResult): String {
        val encoded = json.encodeToString(updated)
        val lines = text.lines().let { if (it.lastOrNull() == "") it.dropLast(1) else it }
        val replaced = lines.map { raw ->
            if (raw.isBlank()) raw
            else try {
                if (jsonLenient.decodeFromString<DetectionResult>(raw).id == updated.id) encoded else raw
            } catch (_: Exception) { raw }
        }
        return if (replaced.isEmpty()) "" else replaced.joinToString("\n") + "\n"
    }

    /** Entfernt File-Sessions, deren Ordnername schon als SAF-Session gelistet ist (Duplikate). */
    fun dropListedNames(fileSessions: List<File>, safNames: Set<String>): List<File> =
        fileSessions.filter { it.name !in safNames }
}
