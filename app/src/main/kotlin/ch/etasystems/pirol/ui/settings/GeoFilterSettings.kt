package ch.etasystems.pirol.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import ch.etasystems.pirol.R
import ch.etasystems.pirol.data.AppPreferences
import ch.etasystems.pirol.ml.GeoCombine
import ch.etasystems.pirol.ml.GeoLogic
import ch.etasystems.pirol.ml.GeoModelFiles
import ch.etasystems.pirol.ml.ModelManager
import ch.etasystems.pirol.ml.RegionInfo
import ch.etasystems.pirol.ui.AppStrings
import kotlinx.coroutines.launch

/**
 * T218: Einstellungen fuer "Automatisch (Standort + Woche)": Geomodel-Bezug, Schwelle,
 * Verknuepfung mit einer Liste, Jahresliste.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColumnScope.GeoFilterSettings(
    appPreferences: AppPreferences,
    modelManager: ModelManager,
    regions: List<RegionInfo>,
    active: Boolean = true  // T218b: false = nur Hinweis + Download zeigen (Region "Auto" nicht gewaehlt)
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var installed by remember { mutableStateOf(GeoModelFiles.isInstalled(context)) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var threshold by remember { mutableStateOf(appPreferences.geoThreshold) }
    var combine by remember { mutableStateOf(GeoCombine.parse(appPreferences.geoCombine)) }
    var yearly by remember { mutableStateOf(appPreferences.geoYearly) }
    var companion by remember { mutableStateOf(appPreferences.geoCompanionRegion) }

    Spacer(modifier = Modifier.height(8.dp))
    Text(stringResource(R.string.settings_geo_hint), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(modifier = Modifier.height(8.dp))

    // Bezug des Geomodels (7,7 MB)
    if (installed) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.settings_geo_installiert), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { modelManager.deleteGeomodel(); installed = false },
                modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.settings_geo_entfernen)) }
        }
    } else {
        Text(stringResource(R.string.settings_geo_fehlt), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error)
        val p = progress
        if (p != null) {
            LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
        } else {
            OutlinedButton(
                onClick = {
                    error = null
                    progress = 0f
                    scope.launch {
                        val ok = modelManager.downloadGeomodel { progress = it }
                        progress = null
                        installed = ok && GeoModelFiles.isInstalled(context)
                        if (!ok) error = modelManager.lastDownloadError ?: "?"
                    }
                },
                modifier = Modifier.heightIn(min = 48.dp)
            ) { Text(stringResource(R.string.settings_geo_laden)) }
        }
        error?.let {
            Text(AppStrings.get(R.string.settings_geo_fehler, it), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error)
        }
    }

    if (!active) return

    // Schwelle
    Spacer(modifier = Modifier.height(8.dp))
    Text(stringResource(R.string.settings_geo_schwelle), style = MaterialTheme.typography.bodyMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(0.01f, 0.03f, 0.05f, 0.10f, 0.15f).forEach { v ->
            FilterChip(
                selected = kotlin.math.abs(threshold - v) < 0.001f,
                onClick = { threshold = v; appPreferences.geoThreshold = v },
                label = { Text("%.2f".format(v)) }
            )
        }
    }

    // Verknuepfung mit Liste
    Spacer(modifier = Modifier.height(8.dp))
    Text(stringResource(R.string.settings_geo_kombination), style = MaterialTheme.typography.bodyMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            GeoCombine.GEO_ONLY to R.string.settings_geo_nur_geo,
            GeoCombine.INTERSECT to R.string.settings_geo_schnittmenge,
            GeoCombine.UNION to R.string.settings_geo_vereinigung
        ).forEach { (c, label) ->
            FilterChip(
                selected = combine == c,
                onClick = { combine = c; appPreferences.geoCombine = c.name },
                label = { Text(stringResource(label)) }
            )
        }
    }
    Text(stringResource(R.string.settings_geo_liste), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        regions.forEach { r ->
            FilterChip(
                selected = companion == r.id,
                onClick = { companion = r.id; appPreferences.geoCompanionRegion = r.id },
                label = { Text(r.nameDe) }
            )
        }
    }

    // Jahresliste
    Row(modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.settings_geo_jahresliste), style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f))
        Switch(checked = yearly, onCheckedChange = { yearly = it; appPreferences.geoYearly = it })
    }
    Text(stringResource(R.string.settings_geo_jahresliste_hint), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}
