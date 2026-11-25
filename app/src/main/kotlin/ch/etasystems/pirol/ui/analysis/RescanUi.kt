package ch.etasystems.pirol.ui.analysis

import ch.etasystems.pirol.ui.help.helpTopic
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ch.etasystems.pirol.R
import ch.etasystems.pirol.ml.RegionInfo
import ch.etasystems.pirol.rescan.RescanInfo
import ch.etasystems.pirol.rescan.RescanModelOffer
import ch.etasystems.pirol.rescan.RescanOptions
import ch.etasystems.pirol.rescan.RescanStatus
import ch.etasystems.pirol.ui.settings.SpeciesGroupSettings
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * T221: Kurzname einer Variante fuer Chips und Kopfzeile ("23.11. 14:30 · Modell").
 * T260: mit Status ("· laeuft", "· abgebrochen bei 12 min"); DONE ohne Zusatz.
 */
@Composable
internal fun variantLabel(info: RescanInfo): String {
    val time = runCatching {
        OffsetDateTime.parse(info.createdAt).format(DateTimeFormatter.ofPattern("dd.MM. HH:mm"))
    }.getOrDefault(info.id)
    val base = "$time · ${info.modelName}"
    return when (info.status) {
        RescanStatus.RUNNING -> "$base · " + stringResource(R.string.analysis_variante_laeuft)
        RescanStatus.ABORTED -> "$base · " +
            stringResource(R.string.analysis_variante_abgebrochen_bei, RescanStatus.minutes(info.scannedSec))
        else -> base
    }
}

/**
 * T221: Varianten-Auswahl (Original | Rescans), Loeschen der aktiven Variante, Fortschritt des Rescans.
 * Erscheint nur, wenn es Varianten gibt oder ein Rescan dieser Session laeuft.
 */
@Composable
internal fun RescanVariantBar(state: AnalysisUiState, viewModel: AnalysisViewModel) {
    val session = state.selectedSession ?: return
    val progress = state.rescanProgress?.takeIf { state.rescanSessionDir == session.sessionDir }
    if (progress != null) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                Text(
                    stringResource(R.string.analysis_rescan_laeuft, (progress * 100).toInt()),
                    style = MaterialTheme.typography.labelMedium
                )
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                // T260: Ergebnisse erscheinen laufend in der Variante
                Text(
                    stringResource(R.string.analysis_rescan_live_hinweis),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    stringResource(R.string.analysis_rescan_hinweis),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { viewModel.cancelRescan() }, modifier = Modifier.helpTopic(R.string.help_rescan_cancel, "6")) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.common_cancel))
            }
        }
    }
    if (state.variants.isEmpty()) return
    var confirmDelete by remember { mutableStateOf<RescanInfo?>(null) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(start = 12.dp)
        ) {
            FilterChip(
                selected = state.activeVariant == null,
                onClick = { viewModel.selectVariant(null) },
                modifier = Modifier.helpTopic(R.string.help_rescan_variant, "6"),
                label = { Text(stringResource(R.string.analysis_variante_original)) }
            )
            state.variants.forEach { v ->
                FilterChip(
                    selected = state.activeVariant == v.id,
                    onClick = { viewModel.selectVariant(v.id) },
                    modifier = Modifier.helpTopic(R.string.help_rescan_variant, "6"),
                    label = { Text(variantLabel(v), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                )
            }
        }
        // T260: laufende Variante nicht loeschbar (erst abbrechen)
        val active = state.variants.firstOrNull { it.id == state.activeVariant }
            ?.takeIf { it.status != RescanStatus.RUNNING }
        if (active != null) {
            IconButton(onClick = { confirmDelete = active }, modifier = Modifier.helpTopic(R.string.help_rescan_variant_delete, "6")) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.analysis_variante_loeschen))
            }
        }
    }
    confirmDelete?.let { v ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.analysis_variante_loeschen)) },
            text = { Text(stringResource(R.string.analysis_variante_loeschen_frage, variantLabel(v))) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = null; viewModel.deleteVariant(v.id) }) {
                    Text(stringResource(R.string.common_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }
}

/** T221: Rescan-Dialog — Modell, Gruppenwahl, Konfidenz-Schwelle und Overlap waehlen. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RescanDialog(viewModel: AnalysisViewModel, onDismiss: () -> Unit) {
    val defaults = remember { viewModel.rescanDefaults() } ?: return
    val offer = remember { viewModel.rescanModelOffer() }  // T295
    val models = offer.offered
    val sessionRateKHz = remember { viewModel.rescanSessionRateHz() / 1000 }
    var allRegions by remember { mutableStateOf<List<RegionInfo>>(emptyList()) }
    LaunchedEffect(Unit) { allRegions = viewModel.rescanRegions() }
    // T239: Gruppenwahl der Session (Voreinstellung), im Dialog aenderbar; T246: einziger Filter
    var groups by remember { mutableStateOf(defaults.groups) }

    var model by remember { mutableStateOf(RescanModelOffer.defaultModel(models, defaults.modelFileName) ?: defaults.modelFileName) }
    var threshold by remember { mutableFloatStateOf(defaults.confidenceThreshold.coerceIn(0.05f, 0.95f)) }
    var overlap by remember { mutableIntStateOf(defaults.overlapPercent) }
    var secondary by remember { mutableStateOf(defaults.secondaryModelFileName) }
    // T273 (B8): nach Import mehrerer Dateien gilt der Dialog fuer alle neuen Sessions
    val batchSize = remember { viewModel.rescanBatchSize() }
    val cancel = { viewModel.clearImportBatch(); onDismiss() }

    AlertDialog(
        onDismissRequest = cancel,
        title = { Text(stringResource(R.string.analysis_rescan)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.analysis_rescan_info),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (batchSize > 1) {
                    Text(
                        stringResource(R.string.analysis_rescan_alle_importierten, batchSize),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.analysis_rescan_modell), style = MaterialTheme.typography.titleSmall)
                if (models.isEmpty()) {
                    Text(stringResource(R.string.analysis_rescan_kein_modell), color = MaterialTheme.colorScheme.error)
                }
                if (offer.hidden.isNotEmpty()) {
                    Text(
                        stringResource(R.string.rescan_model_ultrasonic_hidden, sessionRateKHz),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.helpTopic(R.string.help_rescan_model, "6")
                ) {
                    models.forEach { m ->
                        FilterChip(selected = model == m.fileName, onClick = { model = m.fileName }, label = { Text(m.name) })
                    }
                }
                // T248: Zweitmodell (Voreinstellung wie live); nicht das gewaehlte Hauptmodell
                val secondaryModels = models.filter { it.fileName != model }
                if (secondaryModels.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.analysis_rescan_zweitmodell), style = MaterialTheme.typography.titleSmall)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.helpTopic(R.string.help_rescan_secondary, "6")
                    ) {
                        FilterChip(
                            selected = secondaryModels.none { it.fileName == secondary },
                            onClick = { secondary = null },
                            label = { Text(stringResource(R.string.analysis_rescan_zweitmodell_aus)) }
                        )
                        secondaryModels.forEach { m ->
                            FilterChip(selected = secondary == m.fileName, onClick = { secondary = m.fileName }, label = { Text(m.name) })
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                // Gleiche Gruppenwahl wie in den Einstellungen (Auto = Geomodel mit Session-Position/-Datum)
                Text(stringResource(R.string.analysis_rescan_groups), style = MaterialTheme.typography.titleSmall)
                SpeciesGroupSettings(groups, allRegions) { groups = it }
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.analysis_rescan_schwelle, (threshold * 100).roundToInt()),
                    style = MaterialTheme.typography.titleSmall
                )
                Slider(
                    modifier = Modifier.helpTopic(R.string.help_rescan_threshold, "6"),
                    value = threshold,
                    onValueChange = { threshold = (it * 20).roundToInt() / 20f },
                    valueRange = 0.05f..0.95f,
                    steps = 17
                )
                Text(stringResource(R.string.analysis_rescan_overlap), style = MaterialTheme.typography.titleSmall)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.helpTopic(R.string.help_rescan_overlap, "6")
                ) {
                    listOf(0, 50, 67).forEach { p ->
                        FilterChip(selected = overlap == p, onClick = { overlap = p }, label = { Text("$p %") })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = models.any { it.fileName == model },
                modifier = Modifier.helpTopic(R.string.help_rescan_start, "6"),
                onClick = {
                    onDismiss()
                    viewModel.startRescan(RescanOptions(
                        model, threshold, overlap, groups,
                        secondaryModelFileName = secondary?.takeIf { s -> s != model && models.any { it.fileName == s } }
                    ))
                }
            ) { Text(stringResource(R.string.analysis_rescan_starten)) }
        },
        dismissButton = {
            TextButton(onClick = cancel, modifier = Modifier.helpTopic(R.string.help_dialog_cancel, "6")) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}
