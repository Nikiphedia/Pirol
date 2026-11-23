package ch.etasystems.pirol

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ch.etasystems.pirol.audio.AlarmEvent
import ch.etasystems.pirol.audio.AlarmService
import ch.etasystems.pirol.ml.SpeciesNameResolver
import ch.etasystems.pirol.ui.theme.PirolTheme
import org.koin.android.ext.android.inject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Kleine Alarm-Activity (T135): wird per Full-Screen-Intent der Watchlist-Notification gestartet,
 * weckt den Bildschirm und erscheint ueber dem Sperrbildschirm. "Bestaetigen" nutzt denselben
 * Pfad wie der In-App-Hinweis ([AlarmService.confirmRequests]).
 */
class AlarmActivity : AppCompatActivity() {

    private val alarmService: AlarmService by inject()
    private val speciesNameResolver: SpeciesNameResolver by inject()

    private var request by mutableStateOf<AlarmRequest?>(null)

    private data class AlarmRequest(
        val detectionId: String,
        val scientificName: String,
        val confidence: Float,
        val timestampMs: Long,
        val isTest: Boolean,
        val notificationId: Int
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockscreen()
        request = parse(intent)
        if (request == null) {
            finish()
            return
        }
        setContent {
            PirolTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.errorContainer) {
                    request?.let { AlarmContent(it) }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        request = parse(intent)
    }

    @Suppress("DEPRECATION")
    private fun showOverLockscreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun parse(i: Intent?): AlarmRequest? {
        val sci = i?.getStringExtra(EXTRA_SCIENTIFIC) ?: return null
        return AlarmRequest(
            detectionId = i.getStringExtra(EXTRA_DETECTION_ID) ?: "",
            scientificName = sci,
            confidence = i.getFloatExtra(EXTRA_CONFIDENCE, 0f),
            timestampMs = i.getLongExtra(EXTRA_TIMESTAMP, System.currentTimeMillis()),
            isTest = i.getBooleanExtra(EXTRA_IS_TEST, false),
            notificationId = i.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
        )
    }

    @Composable
    private fun AlarmContent(r: AlarmRequest) {
        val time = remember(r.timestampMs) {
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(r.timestampMs))
        }
        // Artname in der gewaehlten Anzeigesprache (SpeciesNameResolver)
        val common = remember(r.scientificName) { speciesNameResolver.displayName(r.scientificName) }
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = if (r.isTest) stringResource(R.string.ui_watchlist_alarm_test) else stringResource(R.string.ui_watchlist_alarm),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(Modifier.padding(4.dp))
            Text(
                text = common,
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Text(
                text = r.scientificName.replace('_', ' '),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(Modifier.padding(4.dp))
            Text(
                text = stringResource(R.string.ui_konfidenz, (r.confidence * 100).toInt(), time),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(Modifier.padding(16.dp))
            Row(
                modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = { resolve(r, confirm = false) },
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp)
                ) { Text(stringResource(R.string.common_close)) }
                Button(
                    onClick = { resolve(r, confirm = true) },
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp)
                ) { Text(if (r.isTest) "OK" else stringResource(R.string.ui_bestaetigen)) }
            }
        }
    }

    private fun resolve(r: AlarmRequest, confirm: Boolean) {
        alarmService.resolveFromActivity(r.detectionId, r.notificationId, confirm && !r.isTest)
        finish()
    }

    companion object {
        private const val EXTRA_DETECTION_ID = "detection_id"
        private const val EXTRA_SCIENTIFIC = "scientific_name"
        private const val EXTRA_CONFIDENCE = "confidence"
        private const val EXTRA_TIMESTAMP = "timestamp_ms"
        private const val EXTRA_IS_TEST = "is_test"
        private const val EXTRA_NOTIFICATION_ID = "notification_id"

        fun createIntent(context: Context, event: AlarmEvent, notificationId: Int): Intent =
            Intent(context, AlarmActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_DETECTION_ID, event.detectionId)
                putExtra(EXTRA_SCIENTIFIC, event.scientificName)
                putExtra(EXTRA_CONFIDENCE, event.confidence)
                putExtra(EXTRA_TIMESTAMP, event.timestampMs)
                putExtra(EXTRA_IS_TEST, event.isTest)
                putExtra(EXTRA_NOTIFICATION_ID, notificationId)
            }
    }
}
