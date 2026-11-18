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
import ch.etasystems.pirol.MainActivity
import ch.etasystems.pirol.data.AppPreferences
import ch.etasystems.pirol.ml.DetectionResult
import ch.etasystems.pirol.ml.SpeciesNameResolver
import ch.etasystems.pirol.ml.WatchlistMatcher
import ch.etasystems.pirol.ml.WatchlistPriority
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
    private val speciesNameResolver: SpeciesNameResolver
) {

    companion object {
        private const val TAG = "AlarmService"
        private const val CHANNEL_ID = "watchlist_alarm_v2"
        private const val OLD_CHANNEL_ID = "watchlist_alarm"
        private const val NOTIFICATION_ID_BASE = 1000  // Offset zu RecordingService (1)
        private val VIBRATION_PATTERN = longArrayOf(0, 400, 150, 400, 150, 800)
    }

    private var notificationCounter = 0

    // Letzte Alarm-Zeitpunkte pro Art (Cooldown-Tracking, Schluessel normalisiert)
    private val lastAlarmTimes = mutableMapOf<String, Long>()

    // In-App-Hinweis: aktueller Alarm (null = keiner sichtbar)
    private val _activeAlarm = MutableStateFlow<AlarmEvent?>(null)
    val activeAlarm: StateFlow<AlarmEvent?> = _activeAlarm.asStateFlow()

    // "Bestaetigen" im Hinweis -> LiveViewModel setzt die Detektion auf CONFIRMED
    private val _confirmRequests = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val confirmRequests: SharedFlow<String> = _confirmRequests.asSharedFlow()

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        val manager = context.getSystemService(NotificationManager::class.java)
        // Alter Kanal hatte unveraenderbare Einstellungen -> neu anlegen
        manager.deleteNotificationChannel(OLD_CHANNEL_ID)
        val channel = NotificationChannel(
            CHANNEL_ID,
            AppStrings.get(R.string.svc_watchlist_alarm),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = AppStrings.get(R.string.svc_alarm_bei_erkennung_von_watchlist)
            enableVibration(false)  // Vibration steuert die App selbst (Settings-Schalter)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
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
    fun triggerTestAlarm() {
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

    /** Hinweis schliessen und Detektion bestaetigen (nur echte Alarme). */
    fun confirmActiveAlarm() {
        val event = _activeAlarm.value ?: return
        _activeAlarm.value = null
        if (!event.isTest && event.detectionId.isNotEmpty()) {
            _confirmRequests.tryEmit(event.detectionId)
        }
    }

    private fun fire(event: AlarmEvent) {
        if (appPreferences.alarmVibrationEnabled) vibrate()
        if (appPreferences.alarmNotificationEnabled) showNotification(event)
        if (appPreferences.alarmBannerEnabled) _activeAlarm.value = event
    }

    private fun showNotification(event: AlarmEvent) {
        // Ohne POST_NOTIFICATIONS (Android 13+) wuerde notify() stillschweigend verworfen
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            Log.w(TAG, "Benachrichtigungen nicht erlaubt - Alarm-Notification uebersprungen")
            return
        }
        notificationCounter++
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
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText("${event.scientificName.replace('_', ' ')} - $pct% - $time")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(context)
                .notify(NOTIFICATION_ID_BASE + notificationCounter, notification)
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
