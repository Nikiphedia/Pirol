package ch.etasystems.pirol.ui.analysis

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ch.etasystems.pirol.R
import ch.etasystems.pirol.data.importer.AudioImportRequest
import ch.etasystems.pirol.data.importer.AudioProbe
import ch.etasystems.pirol.data.importer.ImportTimeParser
import ch.etasystems.pirol.data.importer.SessionImporter
import ch.etasystems.pirol.data.importer.TimeGuess
import ch.etasystems.pirol.data.importer.TimeSource
import ch.etasystems.pirol.ui.components.MapPointPicker
import java.time.ZoneId

/**
 * T255: Import-Knopf der Session-Liste (48 dp) mit Menue: PIROL-Session als ZIP, als Ordner, Fremdaudio.
 * Die Dateiwahl laeuft ueber SAF; Berechtigungen gelten nur fuer diesen Vorgang.
 */
@Composable
fun ImportMenuButton(viewModel: AnalysisViewModel, enabled: Boolean) {
    var open by remember { mutableStateOf(false) }
    val zipLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importPackage(uri, isFolder = false)
    }
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.importPackage(uri, isFolder = true)
    }
    val audioLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) viewModel.probeAudioImport(uris)
    }
    Box {
        IconButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Filled.FileDownload, contentDescription = stringResource(R.string.import_menu))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.import_zip)) },
                onClick = { open = false; zipLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
                modifier = Modifier.heightIn(min = 48.dp)
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.import_ordner)) },
                onClick = { open = false; folderLauncher.launch(null) },
                modifier = Modifier.heightIn(min = 48.dp)
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.import_audio)) },
                onClick = { open = false; audioLauncher.launch(arrayOf("audio/*", "application/ogg")) },
                modifier = Modifier.heightIn(min = 48.dp)
            )
        }
    }
}

/** T255: Fortschritt des Imports unter der Kopfzeile (nicht blockierend) mit Abbrechen. */
@Composable
fun ImportProgressRow(active: SessionImporter.Active, onCancel: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (active.sessionCount > 1) stringResource(R.string.import_laeuft_n, active.sessionIndex + 1, active.sessionCount)
                else stringResource(R.string.import_laeuft),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            active.fraction?.let { Text("${(it * 100).toInt()} %", style = MaterialTheme.typography.bodySmall) }
            IconButton(onClick = onCancel, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.common_cancel))
            }
        }
        val f = active.fraction
        if (f != null) LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth())
        else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Text(stringResource(R.string.import_hinweis), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun sourceLabel(s: TimeSource): String = stringResource(when (s) {
    TimeSource.GUANO -> R.string.import_quelle_guano
    TimeSource.AUDIOMOTH -> R.string.import_quelle_audiomoth
    TimeSource.BEXT -> R.string.import_quelle_bext
    TimeSource.METADATA -> R.string.import_quelle_metadata
    TimeSource.INFO -> R.string.import_quelle_info
    TimeSource.FILENAME -> R.string.import_quelle_filename
    TimeSource.MODIFIED -> R.string.import_quelle_modified
    TimeSource.MANUAL -> R.string.import_quelle_manual
})

/**
 * T255: Dialog vor dem Fremdaudio-Import: je Datei erkannte Startzeit (aenderbar; fehlt sie, muss sie
 * eingegeben werden), bei mehreren Dateien die Wahl "je Datei eine Session" / "alle zu einer", Ort optional
 * per Karte. [probes] = null zeigt "wird geprueft".
 */
@Composable
fun AudioImportDialog(
    probes: List<AudioProbe>?,
    onDismiss: () -> Unit,
    onStart: (AudioImportRequest) -> Unit
) {
    val zone = remember { ZoneId.systemDefault() }
    if (probes == null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.import_dialog_titel)) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Spacer(Modifier.size(12.dp))
                    Text(stringResource(R.string.import_pruefe))
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } }
        )
        return
    }
    val usable = remember(probes) { probes.filter { it.readable } }
    val texts = remember(probes) {
        mutableStateListOf(*usable.map { p -> p.time?.let { ImportTimeParser.formatInput(it.time, zone) } ?: "" }.toTypedArray())
    }
    var combine by remember(probes) { mutableStateOf(false) }
    var lat by remember(probes) { mutableStateOf<Double?>(null) }
    var lon by remember(probes) { mutableStateOf<Double?>(null) }
    var showMap by remember { mutableStateOf(false) }

    val parsed = texts.map { ImportTimeParser.parseInput(it, zone) }
    val canStart = usable.isNotEmpty() && parsed.all { it != null }

    if (showMap) {
        val start = usable.firstOrNull { it.latitude != null }
        MapPointPicker(
            startLat = lat ?: start?.latitude ?: 46.8,
            startLon = lon ?: start?.longitude ?: 8.2,
            startZoom = if (lat != null || start != null) 14.0 else 7.0,
            onPick = { a, b -> lat = a; lon = b; showMap = false },
            onDismiss = { showMap = false }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.import_dialog_titel)) },
        text = {
            Column {
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(usable) { i, p ->
                        Column {
                            Text(p.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                stringResource(R.string.import_datei_info, formatDuration(p.durationSec), p.sampleRate, p.channels),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            OutlinedTextField(
                                value = texts[i],
                                onValueChange = { texts[i] = it },
                                singleLine = true,
                                isError = parsed[i] == null,
                                label = { Text(stringResource(R.string.import_startzeit)) },
                                supportingText = {
                                    val original = p.time?.let { ImportTimeParser.formatInput(it.time, zone) }
                                    Text(when {
                                        parsed[i] == null && texts[i].isBlank() -> stringResource(R.string.import_zeit_fehlt)
                                        parsed[i] == null -> stringResource(R.string.import_zeit_ungueltig)
                                        p.time != null && texts[i] == original -> stringResource(R.string.import_quelle, sourceLabel(p.time.source))
                                        else -> stringResource(R.string.import_quelle, sourceLabel(TimeSource.MANUAL))
                                    })
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                    val skipped = probes.filter { !it.readable }
                    if (skipped.isNotEmpty()) {
                        itemsIndexed(skipped) { _, p ->
                            Text("${p.name}: " + stringResource(R.string.import_nicht_lesbar),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                if (usable.size > 1) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(
                            stringResource(if (combine) R.string.import_zusammen else R.string.import_zusammen_aus),
                            modifier = Modifier.weight(1f)
                        )
                        Switch(checked = combine, onCheckedChange = { combine = it })
                    }
                }
                Text(
                    if (lat != null && lon != null) stringResource(R.string.import_ort,
                        String.format(java.util.Locale.US, "%.5f, %.5f", lat, lon))
                    else stringResource(R.string.import_ort_keiner),
                    style = MaterialTheme.typography.bodyMedium
                )
                if (usable.any { it.latitude != null }) {
                    Text(stringResource(R.string.import_ort_datei), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            }
        },
        confirmButton = {
            TextButton(
                enabled = canStart,
                onClick = {
                    val files = usable.mapIndexed { i, p ->
                        val t = parsed[i]!!
                        val keep = p.time?.takeIf { ImportTimeParser.formatInput(it.time, zone) == texts[i] }
                        p to (keep ?: TimeGuess(t, TimeSource.MANUAL))
                    }
                    onStart(AudioImportRequest(files, combine && usable.size > 1, lat, lon))
                },
                modifier = Modifier.heightIn(min = 48.dp)
            ) { Text(stringResource(R.string.import_starten)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}

private fun formatDuration(sec: Double?): String {
    if (sec == null) return "–"
    val s = sec.toLong()
    return if (s >= 3600) String.format(java.util.Locale.ROOT, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
    else String.format(java.util.Locale.ROOT, "%d:%02d", s / 60, s % 60)
}
