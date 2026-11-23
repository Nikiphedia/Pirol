package ch.etasystems.pirol.data.importer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import ch.etasystems.pirol.MainActivity
import ch.etasystems.pirol.R
import ch.etasystems.pirol.ui.AppStrings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * T255: Vordergrund-Dienst fuer den Import (wie T252 RescanService): haelt den Prozess am Leben, damit lange
 * Dateien auch bei ausgeschaltetem Bildschirm fertig werden; Fortschritt mit "Abbrechen". Die Arbeit bleibt in
 * [SessionImporter]; der Dienst endet, sobald kein Import mehr laeuft. Typ dataSync (lokale Verarbeitung).
 */
class ImportService : Service() {

    companion object {
        private const val CHANNEL_ID = "pirol_import"
        private const val NOTIFICATION_ID = 3002
        private const val ACTION_CANCEL = "ch.etasystems.pirol.CANCEL_IMPORT"

        /** Aus dem Vordergrund (UI) aufrufen, nachdem der Import gestartet wurde. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ImportService::class.java))
        }
    }

    private val importer: SessionImporter by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watchJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(CHANNEL_ID, AppStrings.get(R.string.svc_import_kanal), NotificationManager.IMPORTANCE_LOW)
            .apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForeground immer zuerst (5-s-Frist nach startForegroundService)
        val n = buildNotification(importer.active.value?.fraction)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
        if (intent?.action == ACTION_CANCEL) importer.cancel()
        if (watchJob?.isActive != true) {
            watchJob = scope.launch {
                var last = -2
                importer.active.collect { a ->
                    if (a == null) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        val p = a.fraction?.let { (it * 100).toInt() } ?: -1
                        if (p != last) {
                            last = p
                            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(a.fraction))
                        }
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    /** Android 15: dataSync-Zeitlimit erreicht -> Import abbrechen (Arbeitsordner wird geloescht) */
    override fun onTimeout(startId: Int, fgsType: Int) {
        importer.cancel()
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(fraction: Float?): Notification {
        val tap = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val cancel = PendingIntent.getService(
            this, 1,
            Intent(this, ImportService::class.java).apply { action = ACTION_CANCEL },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val pct = fraction?.let { (it * 100).toInt() } ?: 0
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PIROL")
            .setContentText(AppStrings.get(R.string.import_laeuft))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(tap)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, pct, fraction == null)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, AppStrings.get(R.string.common_cancel), cancel)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
}
