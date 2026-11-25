package ch.etasystems.pirol.data.repository

import kotlinx.serialization.Serializable
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * T273: Ziel einer Ordner-Umbenennung nach geaenderter Startzeit (Teil des Auftrags [SessionInfoEditPlan]).
 * [oldDay]/[newDay] = Tagesordner `YYYY-MM-DD` (null = direkt in der Basis).
 */
@Serializable
data class SessionRenameTarget(
    val oldName: String,
    val newName: String,
    val oldDay: String? = null,
    val newDay: String? = null
)

/**
 * T273: Session-Ordner, der umbenannt und in einen anderen Tagesordner verschoben werden kann
 * (File-Modus und SAF, siehe SessionFolders.kt). Nach [rename]/[moveTo] zeigen [name], [day], [key] und
 * [files] auf den neuen Ort.
 */
interface SessionFolder {
    /** Aktueller Ordnername */
    val name: String
    /** Aktueller Tagesordner (null = Basis) */
    val day: String?
    /** Session-Schluessel wie in [SessionManager.listSessions] */
    val key: File
    /** Gibt es am Ziel schon einen Ordner [name] (im Tagesordner [day], null = Basis)? */
    fun exists(name: String, day: String?): Boolean
    /** Im selben Elternordner umbenennen; Fehler = Ausnahme */
    fun rename(newName: String)
    /** In den Tagesordner [day] verschieben (null = Basis), Tagesordner bei Bedarf anlegen; Fehler = Ausnahme */
    fun moveTo(day: String?)
    /** Dateizugriff am aktuellen Ort */
    fun files(): SessionFiles
}

/**
 * T273: Session-Ordner nach Aenderung der Startzeit umbenennen und verschieben (reine Logik, JVM-testbar).
 *
 * Neue Session-ID = neue Startzeit (Lokalzeit, Format wie T155/T255) + bisheriger Zufallsteil (uuid6).
 * Tagesordner = Datum der neuen Startzeit, nur wenn Tagesordner eingeschaltet sind; sonst bleibt der Ordner,
 * wo er ist. Die Schritte (erst Umbenennen, dann Verschieben) werden aus dem aktuellen Zustand bestimmt
 * ([remaining]) — ein Wiederholen nach Abbruch fuehrt nur aus, was noch fehlt.
 */
object SessionRename {

    /** Session-ID `yyyy-MM-ddTHH-mm-ss_<rest>` */
    private val ID = Regex("""^\d{4}-\d{2}-\d{2}T\d{2}-\d{2}-\d{2}_(.+)$""")

    enum class Step { RENAME, MOVE }

    /** Ergebnis von [apply]: [moveError] = Verschieben gescheitert (Session gueltig, bleibt im alten Tagesordner). */
    data class Outcome(val renamed: Boolean, val moved: Boolean, val moveError: Exception? = null)

    /** Zufallsteil der Session-ID; null = ID hat nicht das Standardformat (dann keine Umbenennung). */
    fun suffixOf(sessionId: String): String? = ID.matchEntire(sessionId)?.groupValues?.get(1)

    /** Neue Session-ID zur neuen Startzeit in [zone]; null = alte ID nicht im Standardformat. */
    fun newSessionId(oldId: String, newStart: ZonedDateTime, zone: ZoneId): String? {
        val suffix = suffixOf(oldId) ?: return null
        return buildSessionId(newStart.withZoneSameInstant(zone).toLocalDateTime(), suffix)
    }

    /**
     * Ziel der Umbenennung; null = nichts zu tun (gleiche ID und gleicher Tagesordner, oder ID nicht im
     * Standardformat). [currentDay] = aktueller Tagesordner (null = Basis).
     */
    fun target(
        oldId: String,
        currentDay: String?,
        newStart: ZonedDateTime,
        zone: ZoneId,
        dailySubfolder: Boolean
    ): SessionRenameTarget? {
        val newId = newSessionId(oldId, newStart, zone) ?: return null
        val newDay = if (dailySubfolder) newStart.withZoneSameInstant(zone).toLocalDate().toString() else currentDay
        if (newId == oldId && newDay == currentDay) return null
        return SessionRenameTarget(oldId, newId, currentDay, newDay)
    }

    /** Fehlende Schritte vom aktuellen Zustand ([name], [day]) zum Ziel [t]. */
    fun remaining(name: String, day: String?, t: SessionRenameTarget): List<Step> = buildList {
        if (name != t.newName) add(Step.RENAME)
        if (day != t.newDay) add(Step.MOVE)
    }

    /**
     * Fehlende Schritte ausfuehren. Umbenennen nur, wenn der Ordner noch den alten Namen traegt und das Ziel frei
     * ist (sonst Ausnahme, Auftrag bleibt liegen). Scheitert nur das Verschieben (Ziel belegt, SAF-Provider ohne
     * Move), ist die Session trotzdem gueltig (ID und Ordnername stimmen ueberein): Ergebnis mit [Outcome.moveError].
     */
    fun apply(folder: SessionFolder, t: SessionRenameTarget): Outcome {
        val steps = remaining(folder.name, folder.day, t)
        var renamed = false
        if (Step.RENAME in steps) {
            check(folder.name == t.oldName) { "Session-Ordner ${folder.name} passt nicht zum Auftrag (${t.oldName})" }
            check(!folder.exists(t.newName, folder.day)) { "Session-Ordner existiert bereits: ${t.newName}" }
            folder.rename(t.newName)
            renamed = true
        }
        if (Step.MOVE !in steps) return Outcome(renamed, moved = false)
        if (folder.exists(t.newName, t.newDay)) {
            return Outcome(renamed, moved = false, moveError = IllegalStateException("Ziel belegt: ${t.newDay}/${t.newName}"))
        }
        return try {
            folder.moveTo(t.newDay)
            Outcome(renamed, moved = true)
        } catch (e: Exception) {
            Outcome(renamed, moved = false, moveError = e)
        }
    }
}
