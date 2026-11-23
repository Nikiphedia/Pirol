package ch.etasystems.pirol.ui.settings.sections

import ch.etasystems.pirol.ui.settings.SettingsSection
import ch.etasystems.pirol.ui.settings.SettingsAdvancedBlock
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ch.etasystems.pirol.data.AppPreferences
import ch.etasystems.pirol.ml.SpeciesNameResolver
import ch.etasystems.pirol.R
import ch.etasystems.pirol.ui.AppLanguage
import androidx.compose.ui.res.stringResource

/** T241: Sektion Sprache (aus SettingsScreen herausgeloest). */
@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
internal fun LanguageSettingsSection(
    appPreferences: AppPreferences,
    speciesNameResolver: SpeciesNameResolver
) {
    SettingsSection(
        sectionKey = "lang",
        title = stringResource(R.string.settings_section_language),
        appPreferences = appPreferences,
        defaultExpanded = true
    ) {
        Text(stringResource(R.string.settings_sprache_der_app), style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))

        var appLangExpanded by remember { mutableStateOf(false) }
        var appLangTag by remember { mutableStateOf(AppLanguage.currentTag()) }
        val systemLangLabel = stringResource(R.string.settings_systemsprache)

        ExposedDropdownMenuBox(
            expanded = appLangExpanded,
            onExpandedChange = { appLangExpanded = it }
        ) {
            @Suppress("DEPRECATION")
            OutlinedTextField(
                value = AppLanguage.OPTIONS.find { it.first == appLangTag }?.second ?: systemLangLabel,
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.settings_sprache)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(appLangExpanded) },
                modifier = Modifier.menuAnchor().fillMaxWidth()
            )
            ExposedDropdownMenu(
                expanded = appLangExpanded,
                onDismissRequest = { appLangExpanded = false }
            ) {
                (listOf(AppLanguage.SYSTEM_TAG to systemLangLabel) + AppLanguage.OPTIONS).forEach { (tag, name) ->
                    DropdownMenuItem(
                        text = { Text(name) },
                        onClick = {
                            appLangExpanded = false
                            if (tag != appLangTag) {
                                appLangTag = tag
                                // Artnamen folgen der App-Sprache, solange nicht explizit gesetzt
                                if (!appPreferences.speciesLanguageExplicit) {
                                    speciesNameResolver.language = AppLanguage.speciesLanguageForTag(
                                        tag, AppLanguage.systemLanguage()
                                    )
                                }
                                // startet die Activity neu
                                AppLanguage.apply(tag)
                            }
                        }
                    )
                }
            }
        }
    }
}
