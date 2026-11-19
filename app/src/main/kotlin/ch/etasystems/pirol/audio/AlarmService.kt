package ch.etasystems.pirol.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import ch.etasystems.pirol.AlarmActivity
import ch.etasystems.pirol.MainActivity
import ch.etasystems.pirol.data.AppPreferences
import ch.etasystems.pirol.ml.DetectionResult
import ch.etasystems.pirol.ml.SpeciesNameResolver
import ch.etasystems.pirol.ml.VerificationService
import ch.etasystems.pirol.ml.WatchlistMatcher
import ch.etasystems.pirol.ml.WatchlistPriority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import ch.etasystems.pirol.R
import ch.etasystems.pirol.ui.AppStrings

/** Ein ausgeloester Alarm (fuer den In-App-Hinweis). */
data class AlarmEvent(
    val detectionId: String,
    val scientificName: String,
    val commonName: String,
    val confidence: Float,
    val timestampMs: Long,
    val isTest: Boolean = false
)

/**
 * Alarm-Service fuer Watchlist-Detektionen (T20/T58/T124).
 *
 * Keine Android-Service-Klasse, sondern Singleton-Hilfsklasse. Drei Ausgaenge, einzeln in den
 * Settings schaltbar: Vibration (USAGE_ALARM, laeuft auch im Lautlos-Modus), Heads-up-
 * Benachrichtigung (Kanal "Watchlist-Alarm") und In-App-Hinweis ([activeAlarm], wird
 * app-weit ueber alle Tabs angezeigt).
 *
 * Priority: high/normal alarmieren, low nur Badge auf der SpeciesCard.
 */
class AlarmService(
    private val context: Context,
    private val appPreferences: AppPreferences,
    private val speciesNameResolver: SpeciesNameResolver,
    private val verificationService: VerificationService  // T137
) {

    companion object {
        private const val TAG = "AlarmService"
        // T135: Kanal-Einstellungen sind nach dem Anlegen unveraenderbar -> neue ID
        const val CHANNEL_ID = "pirol_watchlist_alarm_v2"
        private val OLD_CHANNEL_IDS = listOf("watchlist_alarm", "watchlist_alarm_v2")
        private const val NOTIFICATION_ID_BASE = 1000  // Offset zu RecordingService (1)
        private val VIBRATION_PATTERN = longArrayOf(0, 400, 150, 400, 150, 800)
    }

    private var notificationCounter = 0

    // Scope fuer den verzoegerten Test-Alarm (T135); kein GlobalScope
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Letzte Alarm-Zeitpunkte pro Art (Cooldown-Tracking, Schluessel normalisiert)
    private val lastAlarmTimes = mutableMapOf<String, Long>()

    // In-App-Hinweis: aktueller Alarm (null = keiner sichtbar)
    private val _activeAlarm = MutableStateFlow<AlarmEvent?>(null)
    val activeAlarm: StateFlow<AlarmEvent?> = _activeAlarm.asStateFlow()

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        val manager = context.getSystemService(NotificationManager::class.java)
        // Alter Kanal hatte unveraenderbare Einstellungen -> neu anlegen
        OLD_CHANNEL_IDS.forEach { manager.deleteNotificationChannel(it) }
        val channel = NotificationChannel(
            CHANNEL_ID,
            AppStrings.get(R.string.svc_watchlist_alarm),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = AppStrings.get(R.string.svc_alarm_bei_erkennung_von_watchlist)
            enableVibration(true)
            vibrationPattern = VIBRATION_PATTERN
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setBypassDnd(false)
        }
        manager.createNotificationChannel(channel)
    }

    /** Cooldown-Map fuer naechste Session leeren (AC6: endSession). */
    fun clearCooldowns() {
        synchronized(lastAlarmTimes) { lastAlarmTimes.clear() }
    }

    /**
     * Alarm fuer eine erkannte Watchlist-Art ausloesen (beachtet Alarm-Pause).
     * Kann von jedem Thread aufgerufen werden.
     */
    fun triggerAlarm(detection: DetectionResult, priority: WatchlistPriority) {
        if (priority == WatchlistPriority.low) {
            Log.d(TAG, "LOW-Watchlist-Match: ${detection.commonName} (nur Badge)")
            return
        }
        val cooldownMs = appPreferences.alarmCooldownMinutes * 60_000L
        val now = System.currentTimeMillis()
        val key = WatchlistMatcher.key(detection.scientificName)
        synchronized(lastAlarmTimes) {
            if (!WatchlistMatcher.isCooldownOver(lastAlarmTimes[key], cooldownMs, now)) {
                Log.d(TAG, "Cooldown aktiv fuer ${detection.commonName}, ueberspringe Alarm")
                return
            }
            lastAlarmTimes[key] = now
        }
        Log.i(TAG, "Alarm ($priority): ${detection.commonName}")
        fire(
            AlarmEvent(
                detectionId = detection.id,
                scientificName = detection.scientificName,
                commonName = speciesNameResolver.resolve(detection.scientificName),
                confidence = detection.confidence,
                timestampMs = detection.timestampMs.takeIf { it > 0 } ?: now
            )
        )
    }

    /** Test-Alarm aus den Settings (ohne Cooldown, nutzt die aktiven Ausgaenge). */
    fun triggerTestAlarm(delayMs: Long = 0L) {
        if (delayMs > 0) {
            scope.launch {
                delay(delayMs)
                triggerTestAlarm(0L)
            }
            return
        }
        val sci = "Turdus_merula"
        fire(
            AlarmEvent(
                detectionId = "",
                scientificName = sci,
                commonName = speciesNameResolver.resolve(sci),
                confidence = 0.95f,
                timestampMs = System.currentTimeMillis(),
                isTest = true
            )
        )
    }

    /** Hinweis in der App schliessen. */
    fun dismissActiveAlarm() {
        _activeAlarm.value = null
    }

    /** true wenn die Full-Screen-Berechtigung fehlt (nur Android 14+), fuer den Settings-Hinweis. */
    fun needsFullScreenHint(): Boolean =
        AlarmPolicy.needsFullScreenHint(Build.VERSION.SDK_INT, canUseFullScreenIntent())

    private fun canUseFullScreenIntent(): Boolean =
        if (Build.VERSION.SDK_INT >= 34) {
            context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        } else true

    /** Aus der AlarmActivity: Alarm beenden, Notification entfernen, optional Detektion bestaetigen. */
    fun resolveFromActivity(detectionId: String, notificationId: Int, confirm: Boolean) {
        _activeAlarm.value = null
        NotificationManagerCompat.from(context).cancel(notificationId)
        if (confirm && detectionId.isNotEmpty()) confirmDetection(detectionId)
    }

    /** Hinweis schliessen und Detektion bestaetigen (nur echte Alarme). */
    fun confirmActiveAlarm() {
        val event = _activeAlarm.value ?: return
        _activeAlarm.value = null
        if (!event.isTest && event.detectionId.isNotEmpty()) {
            confirmDetection(event.detectionId)
        }
    }

    // T137: Bestaetigung ueber den Service-Pfad (unabhaengig vom LiveViewModel)
    private fun confirmDetection(detectionId: String) {
        scope.launch(Dispatchers.IO) { verificationService.confirm(detectionId) }
    }

    private fun fire(event: AlarmEvent) {
        if (appPreferences.alarmVibrationEnabled) vibrate()
        if (appPreferences.alarmNotificationEnabled) showNotification(event)
        if (appPreferences.alarmBannerEnabled) _activeAlarm.value = event
    }

    private fun showNotification(event: AlarmEvent) {
        val allowed = NotificationManagerCompat.from(context).areNotificationsEnabled()
        val presentation = AlarmPolicy.decide(
            notificationEnabled = true,
            notificationsAllowed = allowed,
            sdkInt = Build.VERSION.SDK_INT,
            canUseFullScreenIntent = canUseFullScreenIntent()
        )
        if (presentation == AlarmPresentation.NONE) {
            Log.w(TAG, "Benachrichtigungen nicht erlaubt - Alarm-Notification uebersprungen")
            return
        }
        notificationCounter++
        val notificationId = NOTIFICATION_ID_BASE + notificationCounter
        val pct = (event.confidence * 100).toInt()
        val time = ZonedDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, notificationCounter, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (event.isTest) AppStrings.get(R.string.svc_test, event.commonName) else AppStrings.get(R.string.svc_erkannt, event.commonName)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText("${event.scientificName.replace('_', ' ')} - $pct% - $time")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(false)
            .setVibrate(VIBRATION_PATTERN)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        if (!event.isTest && event.detectionId.isNotEmpty()) {
            val confirmIntent = Intent(context, AlarmActionReceiver::class.java).apply {
                action = AlarmActionReceiver.ACTION_CONFIRM
                putExtra(AlarmActionReceiver.EXTRA_DETECTION_ID, event.detectionId)
                putExtra(AlarmActionReceiver.EXTRA_NOTIFICATION_ID, notificationId)
            }
            val confirmPending = PendingIntent.getBroadcast(
                context, notificationId, confirmIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(0, AppStrings.get(R.string.ui_bestaetigen), confirmPending)
        }

        if (presentation == AlarmPresentation.FULL_SCREEN) {
            // Weckt den Bildschirm und zeigt AlarmActivity ueber dem Sperrbildschirm
            val fsIntent = AlarmActivity.createIntent(context, event, notificationId)
            val fsPending = PendingIntent.getActivity(
                context, notificationId, fsIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.setFullScreenIntent(fsPending, true)
        }

        try {
            NotificationManagerCompat.from(context).notify(notificationId, builder.build())
        } catch (e: SecurityException) {
            Log.w(TAG, "notify() ohne Berechtigung", e)
        }
    }

    private fun vibrate() {
        val vibrator: Vibrator = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }) ?: return
        if (!vibrator.hasVibrator()) return
        val effect = VibrationEffect.createWaveform(VIBRATION_PATTERN, -1)
        // USAGE_ALARM: wird auch im Lautlos-Modus und bei Hintergrund-App nicht unterdrueckt
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        @Suppress("DEPRECATION")
        vibrator.vibrate(effect, attrs)
    }
}
