package ch.etasystems.pirol.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import ch.etasystems.pirol.ml.VerificationService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * T137: "Bestaetigen"-Aktion der Alarm-Benachrichtigung. Funktioniert ohne ViewModel und ohne
 * Activity: bestaetigt ueber [VerificationService] (DetectionListState + verifications.jsonl).
 */
class AlarmActionReceiver : BroadcastReceiver(), KoinComponent {
    private val verificationService: VerificationService by inject()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CONFIRM) return
        val detectionId = intent.getStringExtra(EXTRA_DETECTION_ID).orEmpty()
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
        if (notificationId >= 0) NotificationManagerCompat.from(context).cancel(notificationId)
        if (detectionId.isEmpty()) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                verificationService.confirm(detectionId)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_CONFIRM = "ch.etasystems.pirol.action.ALARM_CONFIRM"
        const val EXTRA_DETECTION_ID = "detection_id"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
    }
}
