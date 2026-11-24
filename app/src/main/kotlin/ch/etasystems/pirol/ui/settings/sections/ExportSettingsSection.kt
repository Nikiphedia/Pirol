package ch.etasystems.pirol.ui.settings.sections

import ch.etasystems.pirol.ui.settings.UploadSettingsSection
import ch.etasystems.pirol.ui.settings.SettingsSection
import ch.etasystems.pirol.ui.settings.SettingsAdvancedBlock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ch.etasystems.pirol.data.AppPreferences
import ch.etasystems.pirol.data.sync.UploadManager
import java.io.File
import ch.etasystems.pirol.R
import androidx.compose.ui.res.stringResource
import ch.etasystems.pirol.ui.AppStrings

/** T241: Sektion Export & Upload. */
@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
internal fun ExportSettingsSection(
    appPreferences: AppPreferences,
    expert: Boolean,
    uploadManager: UploadManager
) {
    var wifiOnly by remember { mutableStateOf(uploadManager.wifiOnly) }
    var autoUpload by remember { mutableStateOf(uploadManager.autoUpload) }

    SettingsSection(
        sectionKey = "s_export",
        title = stringResource(R.string.settings_section_export_upload),
        appPreferences = appPreferences,
        defaultExpanded = false
    ) {
        if (expert) {
            var ravenFieldsExpanded by remember { mutableStateOf(false) }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { ravenFieldsExpanded = !ravenFieldsExpanded }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.settings_raven_export_felder),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = if (ravenFieldsExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (ravenFieldsExpanded) stringResource(R.string.settings_einklappen) else stringResource(R.string.settings_aufklappen)
                )
            }

            AnimatedVisibility(visible = ravenFieldsExpanded) {
                Column {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.settings_spalten_die_in_recording_selections),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    var ravenBeginTime     by remember { mutableStateOf(appPreferences.ravenFieldBeginTime) }
                    var ravenEndTime       by remember { mutableStateOf(appPreferences.ravenFieldEndTime) }
                    var ravenLowFreq       by remember { mutableStateOf(appPreferences.ravenFieldLowFreq) }
                    var ravenHighFreq      by remember { mutableStateOf(appPreferences.ravenFieldHighFreq) }
                    var ravenSpecies       by remember { mutableStateOf(appPreferences.ravenFieldSpecies) }
                    var ravenConfidence    by remember { mutableStateOf(appPreferences.ravenFieldConfidence) }
                    var ravenBeginFile     by remember { mutableStateOf(appPreferences.ravenFieldBeginFile) }
                    var ravenBeginDateTime by remember { mutableStateOf(appPreferences.ravenFieldBeginDateTime) }
                    var ravenLat           by remember { mutableStateOf(appPreferences.ravenFieldLat) }
                    var ravenLon           by remember { mutableStateOf(appPreferences.ravenFieldLon) }
                    var ravenSimultan      by remember { mutableStateOf(appPreferences.ravenFieldSimultan) }
                    var ravenMoeglich      by remember { mutableStateOf(appPreferences.ravenFieldMoeglich) }
                    var ravenGleich        by remember { mutableStateOf(appPreferences.ravenFieldGleich) }
                    var ravenSecondary     by remember { mutableStateOf(appPreferences.ravenFieldSecondary) }  // T224
                    var ravenMemo          by remember { mutableStateOf(appPreferences.ravenFieldMemo) }  // T263

                    listOf(
                        Triple("Begin Time (s)",   ravenBeginTime,     { v: Boolean -> ravenBeginTime = v;     appPreferences.ravenFieldBeginTime = v }),
                        Triple("End Time (s)",      ravenEndTime,       { v: Boolean -> ravenEndTime = v;       appPreferences.ravenFieldEndTime = v }),
                        Triple("Low Freq (Hz)",     ravenLowFreq,       { v: Boolean -> ravenLowFreq = v;       appPreferences.ravenFieldLowFreq = v }),
                        Triple("High Freq (Hz)",    ravenHighFreq,      { v: Boolean -> ravenHighFreq = v;      appPreferences.ravenFieldHighFreq = v }),
                        Triple("Species",           ravenSpecies,       { v: Boolean -> ravenSpecies = v;       appPreferences.ravenFieldSpecies = v }),
                        Triple("Confidence",        ravenConfidence,    { v: Boolean -> ravenConfidence = v;    appPreferences.ravenFieldConfidence = v }),
                        Triple("Begin File",        ravenBeginFile,     { v: Boolean -> ravenBeginFile = v;     appPreferences.ravenFieldBeginFile = v }),
                        Triple("Begin Date Time",   ravenBeginDateTime, { v: Boolean -> ravenBeginDateTime = v; appPreferences.ravenFieldBeginDateTime = v }),
                        Triple("Lat",               ravenLat,           { v: Boolean -> ravenLat = v;           appPreferences.ravenFieldLat = v }),
                        Triple("Lon",               ravenLon,           { v: Boolean -> ravenLon = v;           appPreferences.ravenFieldLon = v }),
                        Triple("Second Model",      ravenSecondary,     { v: Boolean -> ravenSecondary = v;     appPreferences.ravenFieldSecondary = v }),  // T224
                        Triple("Memo",              ravenMemo,          { v: Boolean -> ravenMemo = v;          appPreferences.ravenFieldMemo = v })  // T263
                    ).forEach { (label, checked, onToggle) ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            Switch(
                                checked = checked,
                                onCheckedChange = onToggle
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.settings_wirkt_beim_naechsten_export),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    Text(
                        AppStrings.get(R.string.settings_simultanbeobachtungen),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, top = 4.dp)
                    )
                    listOf(
                        Triple(AppStrings.get(R.string.settings_simultan), ravenSimultan, { v: Boolean -> ravenSimultan = v; appPreferences.ravenFieldSimultan = v }),
                        Triple(AppStrings.get(R.string.settings_moeglich),  ravenMoeglich, { v: Boolean -> ravenMoeglich = v; appPreferences.ravenFieldMoeglich = v }),
                        Triple(AppStrings.get(R.string.settings_gleich),    ravenGleich,   { v: Boolean -> ravenGleich   = v; appPreferences.ravenFieldGleich   = v })
                    ).forEach { (label, checked, onToggle) ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Switch(checked = checked, onCheckedChange = onToggle)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))
        }
        if (expert) {
            Text(stringResource(R.string.settings_export), style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))

            // WLAN-only Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.settings_nur_bei_wlan_exportieren), modifier = Modifier.weight(1f))
                Switch(
                    checked = wifiOnly,
                    onCheckedChange = {
                        wifiOnly = it
                        uploadManager.wifiOnly = it
                        appPreferences.wifiOnly = it
                    }
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Auto-Upload Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.settings_automatisch_nach_aufnahme), modifier = Modifier.weight(1f))
                Switch(
                    checked = autoUpload,
                    onCheckedChange = {
                        autoUpload = it
                        uploadManager.autoUpload = it
                        appPreferences.autoUpload = it
                    }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.settings_automatischer_upload_findet_nur_mit),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
        HumanMaskSettings(appPreferences, expert)  // T261
        UploadSettingsSection(appPreferences)
    }
}
