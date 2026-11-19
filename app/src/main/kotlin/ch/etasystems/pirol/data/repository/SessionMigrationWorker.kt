package ch.etasystems.pirol.data.repository

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import ch.etasystems.pirol.R
import ch.etasystems.pirol.data.AppPreferences
import ch.etasystems.pirol.ui.AppStrings
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import java.util.UUID

/**
 * T141: WorkManager-Worker fuer die Session-Migration (File <-> SAF, beide Richtungen).
 *
 * Laeuft als Foreground-Worker mit Fortschritts-Notification. Die Kopier-Logik liegt in
 * [SessionManager.migrateSessions]; der Worker setzt nur Fortschritt, Ergebnis und (bei Erfolg ohne
 * Fehler und gewuenschtem Wechsel) den neuen Speicherort. Fehlgeschlagene Sessions lassen die Quelle
 * unveraendert; Originale werden nur geloescht, wenn ALLE Sessions fehlerfrei kopiert wurden.
 *
 * Verwendung: [enqueue]. Fortschritt/Ergebnis ueber WorkInfo (progress/outputData, Keys unten).
 */
class SessionMigrationWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params), KoinComponent {

    private val sessionManager: SessionManager by inject()
    private val appPreferences: AppPreferences by inject()

    companion object {
        /** Eindeutiger Work-Name (es laeuft hoechstens eine Migration) */
        const val UNIQUE_NAME = "pirol_session_migration"

        /** Ziel als SAF-Tree-URI (String) — sonst [KEY_TARGET_PATH] */
        const val KEY_TARGET_URI = "target_uri"
        /** Ziel als absoluter Ordnerpfad (File-Modus) */
        const val KEY_TARGET_PATH = "target_path"
        /** Quell-Sessions nach fehlerfreiem Kopieren loeschen */
        const val KEY_DELETE_SOURCE = "delete_source"
        /** Nach Erfolg storageBaseUri auf das Ziel umstellen (File-Ziel = Standard = null) */
        const val KEY_SWITCH_STORAGE = "switch_storage"

        // Progress + Output
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_BYTES = "bytes"
        const val KEY_COPIED = "copied"
        const val KEY_SKIPPED = "skipped"
        const val KEY_FAILED = "failed"
        const val KEY_ERRORS = "errors"

        private const val CHANNEL_ID = "pirol_migration"
        private const val NOTIFICATION_ID = 2001
        private const val RESULT_NOTIFICATION_ID = 2002
        private const val TAG = "SessionMigrationWorker"

        /**
         * Reiht die Migration ein (ersetzt keine laufende). Genau eines von [targetUri] / [targetPath].
         * @return WorkRequest-ID
         */
        fun enqueue(
            context: Context,
            targetUri: String? = null,
            targetPath: String? = null,
            deleteSource: Boolean = false,
            switchStorage: Boolean = true
        ): UUID {
            require((targetUri == null) != (targetPath == null)) { "Genau ein Ziel angeben" }
            val request = OneTimeWorkRequestBuilder<SessionMigrationWorker>()
                .setInputData(workDataOf(
                    KEY_TARGET_URI to targetUri,
                    KEY_TARGET_PATH to targetPath,
                    KEY_DELETE_SOURCE to deleteSource,
                    KEY_SWITCH_STORAGE to switchStorage
                ))
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, request)
            Log.i(TAG, "Migration eingereiht")
            return request.id
        }
    }

    override suspend fun doWork(): Result {
        val targetUri = inputData.getString(KEY_TARGET_URI)
        val targetPath = inputData.getString(KEY_TARGET_PATH)
        val deleteSource = inputData.getBoolean(KEY_DELETE_SOURCE, false)
        val switchStorage = inputData.getBoolean(KEY_SWITCH_STORAGE, true)
        val target = when {
            targetUri != null -> MigrationTarget.Tree(Uri.parse(targetUri))
            targetPath != null -> MigrationTarget.Dir(File(targetPath))
            else -> return Result.failure()
        }

        createNotificationChannel()
        try { setForeground(foregroundInfo(0, 0)) } catch (e: Exception) { Log.w(TAG, "Foreground nicht moeglich", e) }

        val result = try {
            sessionManager.migrateSessions(target, deleteSource) { p ->
                setProgressAsync(workDataOf(KEY_DONE to p.doneSessions, KEY_TOTAL to p.totalSessions, KEY_BYTES to p.bytesCopied))
                try { notifyManager().notify(NOTIFICATION_ID, buildNotification(p.doneSessions, p.totalSessions)) } catch (_: Exception) { }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Migration abgebrochen", e)
            MigrationResult(0, 0L, listOf(e.message ?: "?"), 0, 1)
        }

        if (SessionMigrationLogic.shouldSwitchStorage(switchStorage, result.sessionsFailed)) {
            appPreferences.storageBaseUri = targetUri // File-Ziel (App-Speicher) = null
        }

        val output: Data = workDataOf(
            KEY_COPIED to result.sessionsCopied,
            KEY_SKIPPED to result.sessionsSkipped,
            KEY_FAILED to result.sessionsFailed,
            KEY_BYTES to result.bytesCopied,
            KEY_ERRORS to result.errors.take(5).joinToString("; ").take(2000)
        )
        postResultNotification(result)
        Log.i(TAG, "Migration fertig: ${result.sessionsCopied} kopiert, ${result.sessionsSkipped} uebersprungen, ${result.sessionsFailed} fehlgeschlagen")
        return if (result.sessionsFailed == 0) Result.success(output) else Result.failure(output)
    }

    private fun notifyManager() = NotificationManagerCompat.from(applicationContext)

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            AppStrings.get(R.string.svc_session_migration),
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = AppStrings.get(R.string.svc_sessions_werden_an_neuen_speicherort) }
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(done: Int, total: Int) =
        NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_save)
            .setContentTitle(AppStrings.get(R.string.svc_sessions_werden_verschoben))
            .setContentText(if (total > 0) "$done / $total" else AppStrings.get(R.string.svc_vorbereitung))
            .setProgress(100, SessionMigrationLogic.percent(done, total), total == 0)
            .setOngoing(true)
            .setSilent(true)
            .build()

    private fun foregroundInfo(done: Int, total: Int): ForegroundInfo {
        val n = buildNotification(done, total)
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, n)
        }
    }

    private fun postResultNotification(result: MigrationResult) {
        val text = AppStrings.get(
            R.string.mig_notif_result,
            result.sessionsCopied, result.sessionsSkipped, result.sessionsFailed
        )
        val n = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_save)
            .setContentTitle(AppStrings.get(R.string.mig_notif_done_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()
        try {
            if (notifyManager().areNotificationsEnabled()) notifyManager().notify(RESULT_NOTIFICATION_ID, n)
        } catch (e: SecurityException) {
            Log.w(TAG, "Ergebnis-Notification nicht erlaubt", e)
        }
    }
}
