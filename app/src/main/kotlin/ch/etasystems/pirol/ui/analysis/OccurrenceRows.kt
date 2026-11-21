package ch.etasystems.pirol.ui.analysis

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ch.etasystems.pirol.R
import ch.etasystems.pirol.ml.DetectionResult
import ch.etasystems.pirol.ml.VerificationStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * T167: Aufklapp-Zeile + Einzelvorkommen einer mehrfach erkannten Art.
 * Zugeklappt nur eine schmale Zeile ("N Vorkommen"), aufgeklappt eine Zeile je Fenster
 * mit Ortszeit, Position in der Aufnahme, Statusfarbe und Play/Stop (48 dp).
 *
 * @param playingStartSec Startsekunde des gerade abgespielten Vorkommens dieser Detektion (sonst null)
 */
@Composable
fun OccurrenceSection(
    detection: DetectionResult,
    occurrences: List<DetectionOccurrences.Occurrence>,
    expanded: Boolean,
    onToggle: () -> Unit,
    playingStartSec: Float?,
    playEnabled: Boolean,
    onPlay: (DetectionOccurrences.Episode) -> Unit
) {
    if (occurrences.size < 2) return
    val episodes = DetectionOccurrences.episodes(occurrences)
    Column(modifier = Modifier.fillMaxWidth()) {
        TextButton(
            onClick = onToggle,
            modifier = Modifier.heightIn(min = 48.dp)
        ) {
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                if (episodes.size < occurrences.size) {
                    stringResource(R.string.analysis_vorkommen_episoden, occurrences.size, episodes.size)
                } else {
                    stringResource(R.string.analysis_vorkommen, occurrences.size)
                }
            )
        }
        if (expanded) {
            val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            Column(
                modifier = Modifier.padding(start = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                for (ep in episodes) {
                    val isPlaying = playingStartSec == ep.startSec
                    OccurrenceRow(
                        timeLabel = if (ep.windowCount > 1) {
                            timeFmt.format(Date(ep.timestampMs)) + " – " +
                                timeFmt.format(Date(ep.timestampMs + (ep.durationSec * 1000f).toLong()))
                        } else {
                            timeFmt.format(Date(ep.timestampMs))
                        },
                        positionLabel = formatMinSec(ep.startSec),
                        windowCount = ep.windowCount,
                        durationSec = ep.durationSec,
                        confidence = if (ep.containsAnchor) detection.confidence else null,
                        status = detection.verificationStatus,
                        isPlaying = isPlaying,
                        playEnabled = playEnabled,
                        onPlay = { onPlay(ep) }
                    )
                }
            }
        }
    }
}

@Composable
private fun OccurrenceRow(
    timeLabel: String,
    positionLabel: String,
    windowCount: Int,
    durationSec: Float,
    confidence: Float?,
    status: VerificationStatus,
    isPlaying: Boolean,
    playEnabled: Boolean,
    onPlay: () -> Unit
) {
    // Statusfarben wie in SpeciesCard (CLAUDE.md: nicht aendern)
    val statusColor = when (status) {
        VerificationStatus.CONFIRMED -> Color(0xFF4CAF50)
        VerificationStatus.UNCERTAIN -> Color(0xFFFF9800)
        VerificationStatus.REPLACED -> Color(0xFFD32F2F)
        else -> MaterialTheme.colorScheme.outline
    }
    val rowAlpha = if (status == VerificationStatus.REJECTED || status == VerificationStatus.REPLACED) 0.6f else 1f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .alpha(rowAlpha)
            .clickable(enabled = playEnabled, onClick = onPlay),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(statusColor)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = timeLabel,
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "@ $positionLabel",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (windowCount > 1) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.analysis_episode_fenster, windowCount, durationSec.toInt()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (confidence != null) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.analysis_vorkommen_konfidenz_max, (confidence * 100).toInt()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        IconButton(onClick = onPlay, enabled = playEnabled, modifier = Modifier.size(48.dp)) {
            Icon(
                imageVector = if (isPlaying) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                contentDescription = stringResource(
                    if (isPlaying) R.string.analysis_vorkommen_stop else R.string.analysis_vorkommen_abspielen
                )
            )
        }
    }
}

private fun formatMinSec(s: Float): String {
    val total = s.toInt().coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}
