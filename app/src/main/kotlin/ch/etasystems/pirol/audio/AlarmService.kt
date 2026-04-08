package ch.etasystems.pirol.audio

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.app.NotificationCompat
import ch.etasystems.pirol.MainActivity
import ch.etasystems.pirol.ml.DetectionResult
import ch.etasystems.pirol.ml.WatchlistPriority

/**
 * Alarm-Service fuer Watchlist-Detektionen.
 *
 * Keine Android-Service-Klasse, sondern einfache Hilfsklasse (kein eigener Lifecycle noetig).
 * Erstellt Notification-Channel "watchlist_alarm" (IMPORTANCE_HIGH) und zeigt
 * Notifications + Vibration bei Watchlist-Matches an.
 *
 * Priority-Verhalten:
 * - high: Vibration + persistente Notification
 * - normal: Vibration + Notification
 * - low: Kein Alarm (nur visuelles Badge auf SpeciesCard)
 */
class AlarmService(private val context: Context) {

    companion object {
        private const val TAG = "AlarmService"
        private const val CHANNEL_ID = "watchlist_alarm"
        private const val NOTIFICATION_ID_BASE = 1000  // Offset zu RecordingService (1)
        private const val COOLDOWN_MS = 5 * 60 * 1000L  // 5 Minuten Cooldown pro Art
    }

    private var notificationCounter = 0

    // Letzte Alarm-Zeitpunkte pro Art (Cooldown-Tracking)
    private val lastAlarmTimes = mutableMapOf<String, Long>()

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Arten-Alarm",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Alarm bei Erkennung von Watchlist-Arten"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 300, 200, 300)
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    /**
     * Alarm fuer eine erkannte Watchlist-Art ausloesen.
     *
     * @param detection Die Detektion mit scientificName, commonName, confidence
     * @param priority Watchlist-Priority bestimmt Alarm-Intensitaet
     */
    fun triggerAlarm(detection: DetectionResult, priority: WatchlistPriority) {
        // Cooldown pruefen: max 1 Alarm pro Art pro 5 Minuten
        val now = System.currentTimeMillis()
        val lastTime = lastAlarmTimes[detection.scientificName] ?: 0L
        if (now - lastTime < COOLDOWN_MS) {
            Log.d(TAG, "Cooldown aktiv fuer ${detection.commonName}, ueberspringe Alarm")
            return
        }
        lastAlarmTimes[detection.scientificName] = now

        when (priority) {
            WatchlistPriority.high -> {
                showNotification(detection)
                vibrate()
                Log.i(TAG, "HIGH-Alarm: ${detection.commonName}")
            }
            WatchlistPriority.normal -> {
                showNotification(detection)
                vibrate()
                Log.i(TAG, "NORMAL-Alarm: ${detection.commonName}")
            }
            WatchlistPriority.low -> {
                // Nur visuelles Badge, kein Alarm
                Log.d(TAG, "LOW-Watchlist-Match: ${detection.commonName} (nur Badge)")
            }
        }
    }

    private fun showNotification(detection: DetectionResult) {
        notificationCounter++
        val pct = (detection.confidence * 100).toInt()

        // PendingIntent um App bei Notification-Tap zu oeffnen
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationCounter,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("${detection.commonName} erkannt!")
            .setContentText("${detection.scientificName.replace('_', ' ')} — ${pct}% Konfidenz")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val manager = context.getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID_BASE + notificationCounter, notification)
    }

    private fun vibrate() {
        val vibrator = context.getSystemService(Vibrator::class.java) ?: return
        vibrator.vibrate(
            VibrationEffect.createWaveform(
                longArrayOf(0, 300, 200, 300), -1
            )
        )
    }
}
