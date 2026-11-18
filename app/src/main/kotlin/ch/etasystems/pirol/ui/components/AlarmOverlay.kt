package ch.etasystems.pirol.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ch.etasystems.pirol.audio.AlarmService
import org.koin.compose.koinInject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Grosser Watchlist-Alarm-Hinweis (T124). Wird app-weit ueber allen Tabs angezeigt,
 * solange die App im Vordergrund ist und der Schalter in den Settings aktiv ist.
 */
@Composable
fun AlarmOverlay(alarmService: AlarmService = koinInject()) {
    val event by alarmService.activeAlarm.collectAsState()
    val e = event ?: return

    val time = remember(e.timestampMs) {
        DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(e.timestampMs))
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { /* Klicks nicht an den Inhalt darunter durchreichen */ },
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.padding(24.dp).widthIn(max = 560.dp).fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = if (e.isTest) "WATCHLIST-ALARM (Test)" else "WATCHLIST-ALARM",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Spacer(Modifier.padding(4.dp))
                Text(
                    text = e.commonName,
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    text = e.scientificName.replace('_', ' '),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Spacer(Modifier.padding(4.dp))
                Text(
                    text = "${(e.confidence * 100).toInt()}% Konfidenz - $time",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Spacer(Modifier.padding(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = { alarmService.dismissActiveAlarm() },
                        modifier = Modifier.weight(1f).heightIn(min = 56.dp)
                    ) { Text("Schliessen") }
                    Button(
                        onClick = { alarmService.confirmActiveAlarm() },
                        modifier = Modifier.weight(1f).heightIn(min = 56.dp)
                    ) { Text(if (e.isTest) "OK" else "Bestaetigen") }
                }
            }
        }
    }
}
