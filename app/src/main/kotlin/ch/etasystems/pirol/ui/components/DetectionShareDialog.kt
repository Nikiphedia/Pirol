package ch.etasystems.pirol.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ch.etasystems.pirol.data.export.DetectionExporter
import ch.etasystems.pirol.data.export.DetectionShareRequest
import ch.etasystems.pirol.data.export.ShareFormat
import kotlinx.coroutines.launch
import ch.etasystems.pirol.R
import androidx.compose.ui.res.stringResource
import ch.etasystems.pirol.ui.AppStrings

/**
 * T116: Formatwahl + Export einer Detektion. Erzeugt die Datei im Hintergrund (Fortschritt im Dialog),
 * oeffnet danach das Share-Sheet; Fehler werden ueber [onError] (Snackbar) gemeldet.
 */
@Composable
fun DetectionShareDialog(
    request: DetectionShareRequest,
    exporter: DetectionExporter,
    onDismiss: () -> Unit,
    onError: (String) -> Unit
) {
    val formats = remember { exporter.availableFormats() }
    var selected by remember { mutableStateOf(ShareFormat.WAV) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.ui_detektion_teilen)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.ui_ausschnitt_mit_3_s_davor),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                formats.forEach { format ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                            .clickable(enabled = !busy) { selected = format },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = selected == format, onClick = null, enabled = !busy)
                        Spacer(Modifier.width(12.dp))
                        Text(format.label, modifier = Modifier.padding(vertical = 8.dp))
                    }
                }
                if (busy) {
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    busy = true
                    val format = selected
                    scope.launch {
                        try {
                            val file = exporter.export(request, format) { progress = it }
                            exporter.share(file, format)
                            onDismiss()
                        } catch (e: Exception) {
                            busy = false
                            onError(AppStrings.get(R.string.ui_export_fehlgeschlagen, e.message ?: e.javaClass.simpleName))
                        }
                    }
                }
            ) { Text(stringResource(R.string.ui_teilen)) }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}
