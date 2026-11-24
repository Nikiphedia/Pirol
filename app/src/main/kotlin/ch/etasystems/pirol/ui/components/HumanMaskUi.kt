package ch.etasystems.pirol.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ch.etasystems.pirol.R

/** T261: Hinweiszeile im Export-Dialog: Stimmen werden ausgeblendet, bzw. Warnung wenn ausgeschaltet. */
@Composable
fun HumanMaskNotice(masks: Boolean) {
    if (masks) {
        Text(
            stringResource(R.string.human_mask_hinweis_an),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    } else {
        Text(
            stringResource(R.string.human_mask_hinweis_aus),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    }
}

/** T261: Fortschritt des Mensch-Scans vor einem Export, mit Abbrechen. */
@Composable
fun HumanScanProgressDialog(progress: Float, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.human_mask_scan_titel)) },
        text = {
            Column {
                Text(stringResource(R.string.human_mask_scan_text), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    )
}
