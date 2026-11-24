package ch.etasystems.pirol.ui.settings

import ch.etasystems.pirol.ui.help.helpTopic

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import ch.etasystems.pirol.R
import androidx.compose.ui.res.stringResource

/** T134: Einklappbare Einstellungs-Sektion (Kopfzeile 56 dp, Zustand pro Sektion in AppPreferences). */
@Composable
internal fun SettingsSection(
    sectionKey: String,
    title: String,
    appPreferences: AppPreferences,
    defaultExpanded: Boolean,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by remember { mutableStateOf(appPreferences.isSettingsSectionExpanded(sectionKey, defaultExpanded)) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .then(sectionHelp(sectionKey)?.let { (res, ch) -> Modifier.helpTopic(res, ch) } ?: Modifier)
                    .clickable {
                        expanded = !expanded
                        appPreferences.setSettingsSectionExpanded(sectionKey, expanded)
                    }
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                    content()
                }
            }
        }
    }
}

/** T209: Einklappbarer Unterblock "Erweitert" am Ende einer Sektion (Zustand in AppPreferences, Standard zu). */
@Composable
internal fun SettingsAdvancedBlock(
    sectionKey: String,
    appPreferences: AppPreferences,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by remember { mutableStateOf(appPreferences.isSettingsSectionExpanded(sectionKey, false)) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .helpTopic(R.string.help_settings_advanced, "17")
                .clickable {
                    expanded = !expanded
                    appPreferences.setSettingsSectionExpanded(sectionKey, expanded)
                },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.settings_advanced),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                content()
            }
        }
    }
}

/** T266: Hilfetext und Anleitungskapitel je Einstellungs-Sektion (Kopfzeile im Hilfemodus) */
internal fun sectionHelp(sectionKey: String): Pair<Int, String>? = when (sectionKey) {
    "lang" -> R.string.help_settings_language to "16"
    "s_rec" -> R.string.help_settings_recording to "17"
    "s_model" -> R.string.help_settings_model to "13"
    "s_species" -> R.string.help_settings_species to "11"
    "s_display" -> R.string.help_settings_display to "3"
    "s_geo" -> R.string.help_settings_map_gps to "14"
    "s_export" -> R.string.help_settings_export to "7"
    "s_info" -> R.string.help_settings_info to "17"
    else -> null
}
