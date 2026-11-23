package ch.etasystems.pirol.ui.analysis

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
import ch.etasystems.pirol.ml.GeoLogic
import ch.etasystems.pirol.ml.RegionInfo
import ch.etasystems.pirol.rescan.RescanInfo
import ch.etasystems.pirol.rescan.RescanOptions
import ch.etasystems.pirol.ui.settings.SpeciesGroupSettings
import ch.etasystems.pirol.ui.AppStrings
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** T221: Kurzname einer Variante fuer Chips und Kopfzeile ("23.11. 14:30 · Modell"). */
internal fun variantLabel(info: RescanInfo): String {
    val time = runCatching {
        OffsetDateTime.parse(info.createdAt).format(DateTimeFormatter.ofPattern("dd.MM. HH:mm"))
    }.getOrDefault(info.id)
    return "$time · ${info.modelName}"
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
            }
            IconButton(onClick = { viewModel.cancelRescan() }) {
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
                label = { Text(stringResource(R.string.analysis_variante_original)) }
            )
            state.variants.forEach { v ->
                FilterChip(
                    selected = state.activeVariant == v.id,
                    onClick = { viewModel.selectVariant(v.id) },
                    label = { Text(variantLabel(v), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                )
            }
        }
        val active = state.variants.firstOrNull { it.id == state.activeVariant }
        if (active != null) {
            IconButton(onClick = { confirmDelete = active }) {
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

/** T221: Rescan-Dialog — Modell, Liste, Konfidenz-Schwelle und Overlap waehlen. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RescanDialog(viewModel: AnalysisViewModel, onDismiss: () -> Unit) {
    val defaults = remember { viewModel.rescanDefaults() } ?: return
    val models = remember { viewModel.rescanModels() }
    var allRegions by remember { mutableStateOf<List<RegionInfo>>(emptyList()) }
    LaunchedEffect(Unit) { allRegions = viewModel.rescanRegions() }
    val customRegions = allRegions.filter { it.isCustom }
    // T239: Gruppenwahl der Session (Voreinstellung), im Dialog aenderbar
    var groups by remember { mutableStateOf(defaults.groups ?: viewModel.currentSpeciesGroups()) }

    var model by remember { mutableStateOf(defaults.modelFileName) }
    var region by remember { mutableStateOf(defaults.regionFilter) }
    var threshold by remember { mutableFloatStateOf(defaults.confidenceThreshold.coerceIn(0.05f, 0.95f)) }
    var overlap by remember { mutableIntStateOf(defaults.overlapPercent) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.analysis_rescan)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.analysis_rescan_info),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.analysis_rescan_modell), style = MaterialTheme.typography.titleSmall)
                if (models.isEmpty()) {
                    Text(stringResource(R.string.analysis_rescan_kein_modell), color = MaterialTheme.colorScheme.error)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    models.forEach { m ->
                        FilterChip(selected = model == m.fileName, onClick = { model = m.fileName }, label = { Text(m.name) })
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.analysis_rescan_liste), style = MaterialTheme.typography.titleSmall)
                // Gleiche Auswahl wie in den Einstellungen (T218: Auto = Geomodel mit Session-Position/-Datum)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (listOf(
                        "ch_breeding" to "CH",
                        "ch_all" to "CH+",
                        "central_europe" to "EU",
                        "all" to AppStrings.get(R.string.common_all),
                        GeoLogic.REGION_ID to AppStrings.get(R.string.live_region_auto),
                        ch.etasystems.pirol.ml.RegionalSpeciesFilter.GROUPS_ID to AppStrings.get(R.string.analysis_rescan_groups),  // T232
                        null to AppStrings.get(R.string.common_off)
                    ) + customRegions.map { it.id to (it.nameDe + " (" + AppStrings.get(R.string.settings_eigene_liste) + ")") })
                        .forEach { (id, label) ->
                            FilterChip(selected = region == id, onClick = { region = id }, label = { Text(label) })
                        }
                }
                if (region == ch.etasystems.pirol.ml.RegionalSpeciesFilter.GROUPS_ID) {
                    SpeciesGroupSettings(groups, allRegions) { groups = it }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.analysis_rescan_schwelle, (threshold * 100).roundToInt()),
                    style = MaterialTheme.typography.titleSmall
                )
                Slider(
                    value = threshold,
                    onValueChange = { threshold = (it * 20).roundToInt() / 20f },
                    valueRange = 0.05f..0.95f,
                    steps = 17
                )
                Text(stringResource(R.string.analysis_rescan_overlap), style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0, 50, 67).forEach { p ->
                        FilterChip(selected = overlap == p, onClick = { overlap = p }, label = { Text("$p %") })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = models.any { it.fileName == model },
                onClick = {
                    onDismiss()
                    viewModel.startRescan(RescanOptions(model, region, threshold, overlap, groups))
                }
            ) { Text(stringResource(R.string.analysis_rescan_starten)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}
