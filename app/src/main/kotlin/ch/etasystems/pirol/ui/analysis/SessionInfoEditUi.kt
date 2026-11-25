package ch.etasystems.pirol.ui.analysis

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ch.etasystems.pirol.R
import ch.etasystems.pirol.data.importer.ImportTimeParser
import ch.etasystems.pirol.data.repository.SessionInfoEdit
import ch.etasystems.pirol.data.repository.SessionMetadata
import ch.etasystems.pirol.ui.components.MapPointPicker
import ch.etasystems.pirol.util.parseInstantCompat
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * T257: Dialog "Angaben bearbeiten" fuer importierte Sessions: Startzeit (Textfeld wie im Import-Dialog),
 * Ort (Karte, entfernen) und Bemerkung. Zeigt vor dem Speichern die Zeitdifferenz an.
 * [onSave]: neue Startzeit, Ort (beide null = keiner), Bemerkung.
 */
@Composable
fun SessionInfoEditDialog(
    metadata: SessionMetadata,
    onDismiss: () -> Unit,
    onSave: (ZonedDateTime, Double?, Double?, String) -> Unit
) {
    val zone = remember { ZoneId.systemDefault() }
    val originalText = remember(metadata.startedAt) {
        parseInstantCompat(metadata.startedAt)?.let { ImportTimeParser.formatInput(it.atZone(zone), zone) } ?: ""
    }
    // T274: Auswahl per Datum-/Zeit-Picker; null = unveraendert (exakter alter Start, keine Rundung)
    var picked by remember(metadata.sessionId) { mutableStateOf<ZonedDateTime?>(null) }
    var lat by remember(metadata.sessionId) { mutableStateOf(metadata.latitude) }
    var lon by remember(metadata.sessionId) { mutableStateOf(metadata.longitude) }
    var note by remember(metadata.sessionId) { mutableStateOf(metadata.note.orEmpty()) }
    var showMap by remember { mutableStateOf(false) }

    // Unveraenderter Text = exakt der alte Start (keine Rundung auf Sekunden, delta 0)
    val original = parseInstantCompat(metadata.startedAt)?.atZone(zone)
    val parsed = picked ?: original
    val delta = parsed?.let { SessionInfoEdit.deltaMs(metadata.startedAt, it) }
    val locationChanged = lat != metadata.latitude || lon != metadata.longitude

    if (showMap) {
        MapPointPicker(
            startLat = lat ?: metadata.latitude ?: 46.8,
            startLon = lon ?: metadata.longitude ?: 8.2,
            startZoom = if (lat != null || metadata.latitude != null) 14.0 else 7.0,
            onPick = { a, b -> lat = a; lon = b; showMap = false },
            onDismiss = { showMap = false }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.analysis_angaben_titel)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                DateTimePickerField(
                    value = parsed,
                    zone = zone,
                    onValueChange = { picked = if (originalText.isNotEmpty() && ImportTimeParser.formatInput(it, zone) == originalText) null else it },
                    isError = parsed == null,
                    supportingText = when {
                        parsed == null -> stringResource(R.string.import_zeit_ungueltig)
                        delta == null || delta == 0L -> stringResource(R.string.analysis_angaben_zeit_unveraendert)
                        else -> stringResource(R.string.analysis_angaben_differenz, SessionInfoEdit.formatDelta(delta))
                    }
                )
                if (delta != null && delta != 0L) {
                    Text(
                        stringResource(R.string.analysis_angaben_zeit_hinweis),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text(
                    if (lat != null && lon != null) stringResource(R.string.import_ort,
                        String.format(java.util.Locale.US, "%.5f, %.5f", lat, lon))
                    else stringResource(R.string.analysis_angaben_ort_keiner),
                    style = MaterialTheme.typography.bodyMedium
                )
                if (locationChanged) {
                    Text(
                        stringResource(R.string.analysis_angaben_ort_hinweis),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showMap = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.ui_auf_karte_waehlen))
                    }
                    if (lat != null) {
                        TextButton(onClick = { lat = null; lon = null }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.import_ort_entfernen))
                        }
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.replace('\n', ' ').take(200) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.analysis_bemerkung_zur_session)) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = parsed != null && delta != null,
                onClick = { parsed?.let { onSave(it, lat, lon, note) } },
                modifier = Modifier.heightIn(min = 48.dp)
            ) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    )
}
