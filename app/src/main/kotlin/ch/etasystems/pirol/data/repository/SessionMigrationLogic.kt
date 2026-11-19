package ch.etasystems.pirol.data.repository

import android.net.Uri
import java.io.File
import java.util.Locale

/** T141: Ziel einer Session-Migration. */
sealed interface MigrationTarget {
    /** File-Modus: Basisordner im Dateisystem */
    data class Dir(val base: File) : MigrationTarget
    /** SAF-Modus: Tree-URI des Basisordners */
    data class Tree(val uri: Uri) : MigrationTarget
}

/** Fortschritt einer laufenden Migration. */
data class MigrationProgress(val doneSessions: Int, val totalSessions: Int, val bytesCopied: Long)

/** Gruende, eine Migration nicht zu starten. */
enum class MigrationRefusal { RECORDING_ACTIVE, NO_SESSIONS, SAME_LOCATION }

/** Reine Entscheidungs- und Formatlogik der Migration (JVM-testbar). */
object SessionMigrationLogic {

    /** Entscheidet, ob eine Migration verweigert wird; null = erlaubt. */
    fun refusal(recordingActive: Boolean, sessionCount: Int, sameLocation: Boolean): MigrationRefusal? = when {
        recordingActive -> MigrationRefusal.RECORDING_ACTIVE
        sameLocation -> MigrationRefusal.SAME_LOCATION
        sessionCount <= 0 -> MigrationRefusal.NO_SESSIONS
        else -> null
    }

    /** Originale werden nur geloescht, wenn gewuenscht UND keine einzige Session fehlgeschlagen ist. */
    fun shouldDeleteSources(deleteRequested: Boolean, failedCount: Int): Boolean =
        deleteRequested && failedCount == 0

    /** Der Speicherort wird nur umgestellt, wenn alles ohne Fehler kopiert (oder bereits am Ziel) ist. */
    fun shouldSwitchStorage(switchRequested: Boolean, failedCount: Int): Boolean =
        switchRequested && failedCount == 0

    /** Menschenlesbare Groesse (Dezimal-Einheiten, Punkt als Trenner). */
    fun formatSize(bytes: Long): String = when {
        bytes < 1_000L -> "$bytes B"
        bytes < 1_000_000L -> String.format(Locale.US, "%.1f KB", bytes / 1_000.0)
        bytes < 1_000_000_000L -> String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)
        else -> String.format(Locale.US, "%.2f GB", bytes / 1_000_000_000.0)
    }

    private val DATE_DIR = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")

    /** Tages-Unterordner einer Session (Elternordner im Format YYYY-MM-DD) oder null. */
    fun dayDirOf(sessionKey: File): String? =
        sessionKey.parentFile?.name?.takeIf { DATE_DIR.matches(it) }

    /** Fortschritt 0..100 fuer die Notification. */
    fun percent(done: Int, total: Int): Int = if (total <= 0) 0 else (done * 100 / total).coerceIn(0, 100)
}
