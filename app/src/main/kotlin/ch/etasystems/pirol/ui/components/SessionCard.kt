package ch.etasystems.pirol.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ch.etasystems.pirol.ui.analysis.SessionSummary
import ch.etasystems.pirol.util.parseInstantCompat
import java.time.Duration
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Card fuer eine Session in der Session-Liste.
 * Zeigt Datum, Dauer (mm:ss), Detektionen, Dateigrösse, GPS, Region.
 *
 * T52: Regress-Fix — nutzt parseInstantCompat() statt Instant.parse()
 *      damit Stempel mit Offset ("+02:00") korrekt geparst werden.
 */
@Composable
fun SessionCard(
    summary: SessionSummary,
    onClick: () -> Unit,
    onDelete: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val meta = summary.metadata
    val formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm", Locale.GERMAN)
    val zone = ZoneId.systemDefault()

    // Start-Zeitpunkt formatieren (T52: parseInstantCompat statt Instant.parse)
    val startInstant = parseInstantCompat(meta.startedAt)
    val startStr = startInstant?.atZone(zone)?.format(formatter) ?: meta.startedAt

    // End-Zeitpunkt + Dauer berechnen (T52: parseInstantCompat)
    val endInstant = meta.endedAt?.let { parseInstantCompat(it) }
    val endStr = endInstant?.atZone(zone)?.format(
        DateTimeFormatter.ofPattern("HH:mm", Locale.GERMAN)
    ) ?: "–"

    // T52: Dauer als mm:ss statt "X Min" (kein toMinutesPart/toSecondsPart — API 31 Problem vermeiden)
    val durationLabel: String? = if (startInstant != null && endInstant != null) {
        val total = Duration.between(startInstant, endInstant).seconds
        "%02d:%02d".format(total / 60, total % 60)
    } else null

    // T52: WAV-Dateigrösse in MB
    val sizeLabel = if (summary.recordingSizeBytes > 0)
        "%.1f MB".format(summary.recordingSizeBytes / 1_048_576f)
    else null

    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Zeile 1: Datum + Uhrzeit + Delete
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "$startStr – $endStr",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                if (onDelete != null) {
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = "Session loeschen",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Zeile 2: Dauer mm:ss · Detektionen · Verifizierte · MB
            Row {
                val parts = mutableListOf<String>()
                if (durationLabel != null) parts.add(durationLabel)
                parts.add("${summary.detectionCount} Detektionen")
                if (summary.verifiedCount > 0) parts.add("${summary.verifiedCount} \u2713")
                if (sizeLabel != null) parts.add(sizeLabel)
                Text(
                    text = parts.joinToString(" \u00B7 "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Zeile 3: GPS + Region (falls vorhanden)
            if (meta.latitude != null && meta.longitude != null || meta.regionFilter != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Row {
                    val infoParts = mutableListOf<String>()
                    if (meta.latitude != null && meta.longitude != null) {
                        infoParts.add("%.2f\u00B0N, %.2f\u00B0E".format(meta.latitude, meta.longitude))
                    }
                    if (meta.regionFilter != null) {
                        infoParts.add(meta.regionFilter)
                    }
                    Text(
                        text = infoParts.joinToString(" \u00B7 "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}
