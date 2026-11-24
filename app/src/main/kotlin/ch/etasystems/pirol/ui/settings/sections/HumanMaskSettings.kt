package ch.etasystems.pirol.ui.settings.sections

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ch.etasystems.pirol.R
import ch.etasystems.pirol.data.AppPreferences

/**
 * T261: Schalter "Menschliche Stimmen im Export ausblenden" (Default an). Ausschalten nur nach Warnung.
 * Experten: Schwelle des Mensch-Scores.
 */
@Composable
internal fun HumanMaskSettings(appPreferences: AppPreferences, expert: Boolean) {
    var enabled by remember { mutableStateOf(appPreferences.exportMaskHumans) }
    var confirmOff by remember { mutableStateOf(false) }

    Text(stringResource(R.string.human_mask_settings_titel), style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(stringResource(R.string.human_mask_settings_schalter), modifier = Modifier.weight(1f))
        Switch(
            checked = enabled,
            onCheckedChange = { on ->
                if (on) {
                    enabled = true
                    appPreferences.exportMaskHumans = true
                } else {
                    confirmOff = true  // erst nach Bestaetigung ausschalten
                }
            }
        )
    }
    Text(
        stringResource(if (enabled) R.string.human_mask_settings_info else R.string.human_mask_hinweis_aus),
        style = MaterialTheme.typography.bodySmall,
        color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
    )
    if (expert && enabled) {
        var threshold by remember { mutableFloatStateOf(appPreferences.exportHumanThreshold) }
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.human_mask_settings_schwelle, (threshold * 100).toInt()),
            style = MaterialTheme.typography.bodyMedium
        )
        Slider(
            value = threshold,
            onValueChange = { threshold = (Math.round(it * 100f) / 100f) },
            onValueChangeFinished = { appPreferences.exportHumanThreshold = threshold },
            valueRange = 0.02f..0.5f,
            steps = 47
        )
        Text(
            stringResource(R.string.human_mask_settings_schwelle_info),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    Spacer(Modifier.height(16.dp))
    HorizontalDivider()
    Spacer(Modifier.height(16.dp))

    if (confirmOff) {
        AlertDialog(
            onDismissRequest = { confirmOff = false },
            title = { Text(stringResource(R.string.human_mask_warnung_titel)) },
            text = { Text(stringResource(R.string.human_mask_warnung_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmOff = false
                        enabled = false
                        appPreferences.exportMaskHumans = false
                    },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) { Text(stringResource(R.string.human_mask_warnung_ausschalten)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmOff = false }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.human_mask_warnung_behalten))
                }
            }
        )
    }
}
