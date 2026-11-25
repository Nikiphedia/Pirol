package ch.etasystems.pirol.rescan

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
import android.os.PowerManager
import android.util.Log
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
 * T252: Vordergrund-Dienst fuer den Rescan, damit er auch bei ausgeschaltetem Bildschirm fertig wird.
 * Die Arbeit selbst bleibt in [RescanEngine] (T250); der Dienst haelt nur den Prozess am Leben,
 * zeigt Fortschritt mit "Abbrechen" und beendet sich, sobald kein Rescan mehr aktiv ist.
 *
 * Typ dataSync: Daten werden lokal verarbeitet (Aufnahme -> Detektionen), kein Mikrofon/Standort.
 * shortService (3 min) ist fuer lange Aufnahmen zu knapp; Android 15 begrenzt dataSync auf ca. 6 h
 * pro 24 h, bei Ablauf ([onTimeout]) wird der Rescan abgebrochen und der Dienst beendet.
 * Pro Aufnahme kein gleichzeitiger Betrieb mit der Aufnahme: die Sperre liegt im ViewModel/in der Engine.
 */
class RescanService : Service() {

    companion object {
        private const val CHANNEL_ID = "pirol_rescan"
        private const val NOTIFICATION_ID = 3001
        private const val TAG = "RescanService"
        /** T289: Sicherung, falls die Freigabe ausbleibt (entspricht dem dataSync-Limit von ca. 6 h) */
        private const val WAKE_LOCK_TIMEOUT_MS = 6L * 60 * 60 * 1000
        private const val ACTION_CANCEL = "ch.etasystems.pirol.CANCEL_RESCAN"

        /** Aus dem Vordergrund (UI) aufrufen, nachdem [RescanEngine.start] den Rescan gestartet hat. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, RescanService::class.java))
        }
    }

    private val engine: RescanEngine by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watchJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            CHANNEL_ID, AppStrings.get(R.string.svc_rescan_kanal), NotificationManager.IMPORTANCE_LOW
        ).apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForeground immer zuerst (5-s-Frist nach startForegroundService)
        val pct = ((engine.active.value?.progress ?: 0f) * 100).toInt()
        val n = buildNotification(pct)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
        acquireWakeLock()
        if (intent?.action == ACTION_CANCEL) engine.cancel()
        if (watchJob?.isActive != true) {
            watchJob = scope.launch {
                var last = -1
                engine.active.collect { a ->
                    if (a == null) {
                        releaseWakeLock()
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        val p = (a.progress * 100).toInt()
                        if (p != last) {
                            last = p
                            getSystemService(NotificationManager::class.java)
                                .notify(NOTIFICATION_ID, buildNotification(p))
                        }
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    /** Android 15: dataSync-Zeitlimit erreicht -> Rescan abbrechen, Dienst beenden */
    override fun onTimeout(startId: Int, fgsType: Int) {
        engine.cancel()
        releaseWakeLock()
        stopSelf()
    }

    /** T289: PARTIAL_WAKE_LOCK mit Timeout; mehrfaches Aufrufen haelt nur eine Sperre */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        runCatching {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pirol:rescan").apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
        }.onFailure { Log.w(TAG, "T289 WakeLock nicht erhalten", it) }
    }

    /** Freigabe bei Ende, Abbruch, Fehler und Zerstoerung des Dienstes (idempotent) */
    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
            .onFailure { Log.w(TAG, "T289 WakeLock-Freigabe fehlgeschlagen", it) }
        wakeLock = null
    }

    override fun onDestroy() {
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(pct: Int): Notification {
        val tap = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val cancel = PendingIntent.getService(
            this, 1,
            Intent(this, RescanService::class.java).apply { action = ACTION_CANCEL },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PIROL")
            .setContentText(AppStrings.get(R.string.analysis_rescan_laeuft, pct))
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(tap)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, pct, false)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, AppStrings.get(R.string.common_cancel), cancel)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
}
