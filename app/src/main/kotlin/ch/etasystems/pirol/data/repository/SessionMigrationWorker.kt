package ch.etasystems.pirol.data.repository

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import ch.etasystems.pirol.data.AppPreferences
import java.io.File
import java.util.UUID

/**
 * WorkManager CoroutineWorker fuer Session-Migration im Hintergrund.
 *
 * Kopiert alle Sessions vom aktuellen Speicherort zum Zielverzeichnis.
 * Schreibt Fortschritt als Progress-Notification.
 * Nach Erfolg: aktualisiert storageBaseUri in AppPreferences.
 *
 * Verwendung:
 *   SessionMigrationWorker.enqueue(context, targetPath, newStorageUri)
 */
class SessionMigrationWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        /** Zielpfad fuer die Migration (als absoluter String) */
        const val KEY_TARGET_PATH = "target_path"
        /** Neuer storageBaseUri-Wert der nach Erfolg gespeichert wird (kann null sein) */
        const val KEY_NEW_STORAGE_URI = "new_storage_uri"
        /** Falls true: Quelldateien nach erfolgreichem Kopieren loeschen */
        const val KEY_DELETE_SOURCE = "delete_source"

        private const val CHANNEL_ID = "pirol_migration"
        private const val NOTIFICATION_ID = 2001
        private const val TAG = "SessionMigrationWorker"

        /**
         * Reiht einen Migration-Job in WorkManager ein.
         *
         * @param context Context
         * @param targetPath Absoluter Pfad des Zielverzeichnisses
         * @param newStorageUri Neuer SAF-URI-String der nach Erfolg in AppPreferences gespeichert wird
         * @param deleteSource Falls true: Quell-Sessions nach Kopieren loeschen
         * @return WorkRequest-ID
         */
        fun enqueue(
            context: Context,
            targetPath: String,
            newStorageUri: String? = null,
            deleteSource: Boolean = false
        ): UUID {
            val request = OneTimeWorkRequestBuilder<SessionMigrationWorker>()
                .setInputData(workDataOf(
                    KEY_TARGET_PATH to targetPath,
                    KEY_NEW_STORAGE_URI to newStorageUri,
                    KEY_DELETE_SOURCE to deleteSource
                ))
                .build()
            WorkManager.getInstance(context).enqueue(request)
            Log.i(TAG, "Migration-Worker eingereiht → $targetPath")
            return request.id
        }
    }

    override suspend fun doWork(): Result {
        val targetPath = inputData.getString(KEY_TARGET_PATH) ?: return Result.failure()
        val newStorageUri = inputData.getString(KEY_NEW_STORAGE_URI)
        val deleteSource = inputData.getBoolean(KEY_DELETE_SOURCE, false)

        val targetBase = File(targetPath)
        val appPreferences = AppPreferences(applicationContext)
        val sessionManager = SessionManager(applicationContext, appPreferences)

        createNotificationChannel()
        setForeground(buildForegroundInfo(0, 0))

        val sessionDirs = sessionManager.listSessions()
        val total = sessionDirs.size

        if (total == 0) {
            // Nichts zu migrieren — nur Pref setzen
            if (newStorageUri != null) appPreferences.storageBaseUri = newStorageUri
            return Result.success()
        }

        var copied = 0
        val errors = mutableListOf<String>()

        sessionDirs.forEachIndexed { index, sessionDir ->
            val targetDir = File(targetBase, sessionDir.name)
            if (targetDir.exists()) {
                Log.i(TAG, "Session ${sessionDir.name} bereits am Ziel — uebersprungen")
            } else {
                try {
                    val ok = sessionDir.copyRecursively(targetDir, overwrite = false)
                    if (ok) {
                        copied++
                        if (deleteSource) sessionDir.deleteRecursively()
                        Log.i(TAG, "Migriert: ${sessionDir.name}")
                    } else {
                        errors.add(sessionDir.name)
                        targetDir.deleteRecursively()
                    }
                } catch (e: Exception) {
                    errors.add("${sessionDir.name}: ${e.message}")
                    targetDir.deleteRecursively()
                    Log.e(TAG, "Migration fehlgeschlagen: ${sessionDir.name}", e)
                }
            }
            setForeground(buildForegroundInfo(index + 1, total))
        }

        return if (errors.isEmpty()) {
            if (newStorageUri != null) appPreferences.storageBaseUri = newStorageUri
            Log.i(TAG, "Migration abgeschlossen: $copied Sessions kopiert")
            Result.success()
        } else {
            Log.e(TAG, "Migration teilweise fehlgeschlagen: $errors")
            Result.failure()
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Session-Migration",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Sessions werden an neuen Speicherort kopiert"
        }
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun buildForegroundInfo(progress: Int, total: Int): ForegroundInfo {
        val progressPercent = if (total > 0) (progress * 100 / total) else 0
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_save)
            .setContentTitle("Sessions werden verschoben")
            .setContentText(if (total > 0) "$progress / $total" else "Vorbereitung...")
            .setProgress(100, progressPercent, total == 0)
            .setOngoing(true)
            .setSilent(true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification)
    }
}
