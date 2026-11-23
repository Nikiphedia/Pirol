package ch.etasystems.pirol.ui.settings.sections

import ch.etasystems.pirol.ui.settings.SettingsSection
import ch.etasystems.pirol.ui.settings.SettingsAdvancedBlock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ch.etasystems.pirol.data.AppPreferences
import kotlin.math.roundToInt
import ch.etasystems.pirol.R
import androidx.compose.ui.res.stringResource

/** T241: Sektion Karte & GPS. */
@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
internal fun MapGpsSettingsSection(
    appPreferences: AppPreferences,
    expert: Boolean
) {
    SettingsSection(
        sectionKey = "s_geo",
        title = stringResource(R.string.settings_section_map_gps),
        appPreferences = appPreferences,
        defaultExpanded = false
    ) {
        Text("GPS", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))

        // T152: GPS-Modus
        var gpsMode by remember { mutableStateOf(appPreferences.gpsMode) }
        Text(stringResource(R.string.settings_gps_mode), style = MaterialTheme.typography.bodyMedium)
        Spacer(modifier = Modifier.height(4.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                ch.etasystems.pirol.data.GPS_MODE_CONTINUOUS to R.string.settings_gps_mode_continuous,
                ch.etasystems.pirol.data.GPS_MODE_ON_DETECTION to R.string.settings_gps_mode_on_detection
            ).forEach { (mode, labelRes) ->
                FilterChip(
                    selected = gpsMode == mode,
                    onClick = {
                        gpsMode = mode
                        appPreferences.gpsMode = mode
                    },
                    label = { Text(stringResource(labelRes)) }
                )
            }
        }
        val gpsOnDetection = gpsMode == ch.etasystems.pirol.data.GPS_MODE_ON_DETECTION
        if (gpsOnDetection) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.settings_gps_mode_on_detection_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(16.dp))
        // T191: Route aufzeichnen
        var trackRecordingEnabled by remember { mutableStateOf(appPreferences.trackRecordingEnabled) }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_track_recording), style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = stringResource(R.string.settings_track_recording_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = trackRecordingEnabled,
                onCheckedChange = {
                    trackRecordingEnabled = it
                    appPreferences.trackRecordingEnabled = it
                }
            )
        }
        if (trackRecordingEnabled && gpsOnDetection) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.settings_track_recording_on_detection_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary
            )
        }
        if (expert) {
            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))
            SettingsAdvancedBlock(sectionKey = "adv_geo", appPreferences = appPreferences) {
                // GPS-Intervall
                var selectedGpsInterval by remember { mutableIntStateOf(appPreferences.gpsIntervalSeconds) }
                Text(stringResource(R.string.settings_gps_intervall), style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.settings_kuerzere_intervalle_genauere_positio),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(2 to "2s", 5 to "5s", 10 to "10s", 20 to "20s", 60 to "60s").forEach { (sec, label) ->
                        FilterChip(
                            selected = selectedGpsInterval == sec,
                            enabled = !gpsOnDetection,
                            onClick = {
                                selectedGpsInterval = sec
                                appPreferences.gpsIntervalSeconds = sec
                            },
                            label = { Text(label) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.settings_wirkt_ab_naechster_aufnahme),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Accuracy-Filter
                var gpsMaxAccuracy by remember { mutableFloatStateOf(appPreferences.gpsMaxAccuracyMeters) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_max_gps_ungenauigkeit), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = stringResource(R.string.settings_fixes_ungenauer_als_dieser_wert),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${gpsMaxAccuracy.roundToInt()} m",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Slider(
                    value = gpsMaxAccuracy,
                    onValueChange = { v ->
                        gpsMaxAccuracy = v
                        appPreferences.gpsMaxAccuracyMeters = v
                    },
                    valueRange = 10f..200f,
                    steps = 18  // 10 m Raster
                )

                Spacer(modifier = Modifier.height(12.dp))

                // GPS-Smoothing Toggle
                var gpsSmoothingEnabled by remember { mutableStateOf(appPreferences.gpsSmoothingEnabled) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_gps_smoothing), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = stringResource(R.string.settings_median_ueber_5_fixes_reduziert),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = gpsSmoothingEnabled,
                        onCheckedChange = {
                            gpsSmoothingEnabled = it
                            appPreferences.gpsSmoothingEnabled = it
                        }
                    )
                }
            }
        }
    }
}
