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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ch.etasystems.pirol.data.AppPreferences
import ch.etasystems.pirol.ml.SpeciesNameResolver
import ch.etasystems.pirol.audio.dsp.SpectrogramConfig
import ch.etasystems.pirol.data.SpectrogramSettingsRepository
import ch.etasystems.pirol.ui.components.SpectrogramSettingsPanel
import org.koin.compose.koinInject
import ch.etasystems.pirol.R
import androidx.compose.ui.res.stringResource
import ch.etasystems.pirol.ui.AppStrings

/** T241: Sektion Anzeige. */
@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
internal fun DisplaySettingsSection(
    appPreferences: AppPreferences,
    expert: Boolean,
    speciesNameResolver: SpeciesNameResolver
) {
    SettingsSection(
        sectionKey = "s_display",
        title = stringResource(R.string.settings_section_display_main),
        appPreferences = appPreferences,
        defaultExpanded = false
    ) {
        if (expert) {
            Text(stringResource(R.string.settings_artennamen), style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))

            var langExpanded by remember { mutableStateOf(false) }
            // "" = folgt der App-Sprache (nicht explizit gesetzt)
            var selectedLang by remember {
                mutableStateOf(if (appPreferences.speciesLanguageExplicit) appPreferences.speciesLanguage else "")
            }
            val followAppLabel = stringResource(R.string.settings_wie_app_sprache)

            ExposedDropdownMenuBox(
                expanded = langExpanded,
                onExpandedChange = { langExpanded = it }
            ) {
                @Suppress("DEPRECATION")
                OutlinedTextField(
                    value = SpeciesNameResolver.AVAILABLE_LANGUAGES
                        .find { it.first == selectedLang }?.second ?: followAppLabel,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.settings_sprache)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(langExpanded) },
                    modifier = Modifier.menuAnchor().fillMaxWidth()
                )
                ExposedDropdownMenu(
                    expanded = langExpanded,
                    onDismissRequest = { langExpanded = false }
                ) {
                    (listOf("" to followAppLabel) + SpeciesNameResolver.AVAILABLE_LANGUAGES).forEach { (code, name) ->
                        DropdownMenuItem(
                            text = { Text(name) },
                            onClick = {
                                selectedLang = code
                                if (code.isEmpty()) {
                                    appPreferences.speciesLanguageExplicit = false
                                } else {
                                    appPreferences.speciesLanguage = code
                                    appPreferences.speciesLanguageExplicit = true
                                }
                                speciesNameResolver.language = appPreferences.effectiveSpeciesLanguage()
                                langExpanded = false
                            }
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
        // T220: Artnamen wissenschaftlich anzeigen (Landesname als Untertitel)
        var sciNames by remember { mutableStateOf(appPreferences.scientificNamesDisplay) }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_artnamen_wiss))
                Text(
                    AppStrings.get(R.string.settings_artnamen_wiss_hinweis),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = sciNames,
                onCheckedChange = {
                    sciNames = it
                    appPreferences.scientificNamesDisplay = it
                    speciesNameResolver.scientificNames = it
                }
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(16.dp))
        val compactCard by appPreferences.compactCardSetting.flow.collectAsState()
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_artkarte_kompakt))
                Text(
                    AppStrings.get(R.string.settings_artkarte_kompakt_hinweis),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = compactCard,
                onCheckedChange = { appPreferences.compactSpeciesCard = it }
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(16.dp))
        val fullscreen by appPreferences.fullscreenFlow.collectAsState()
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_fullscreen), style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = stringResource(R.string.settings_fullscreen_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = fullscreen, onCheckedChange = { appPreferences.fullscreen = it })
        }
        if (expert) {
            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))

            // SpectrogramConfig Chips: Voegel / Fledermaus / Breitband
            var selectedSpecConfig by remember { mutableStateOf(appPreferences.spectrogramConfigName) }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    "BIRDS" to AppStrings.get(R.string.live_voegel),
                    "BATS" to AppStrings.get(R.string.live_fledermaus),
                    "WIDEBAND" to AppStrings.get(R.string.live_breitband)
                ).forEach { (configName, label) ->
                    FilterChip(
                        selected = selectedSpecConfig == configName,
                        onClick = {
                            selectedSpecConfig = configName
                            appPreferences.spectrogramConfigName = configName
                        },
                        label = { Text(label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // T107: Darstellung (Palette, Kontrast, Gamma, Deckel, Frequenz, Zeitfenster) —
            // dieselben Regler und derselbe Zustand wie im Langdruck-Panel von Live- und Analyse-Tab.
            val specRepo: SpectrogramSettingsRepository = koinInject()
            val specSettings by specRepo.settings.collectAsState()
            val specConfig = when (selectedSpecConfig) {
                "BATS" -> SpectrogramConfig.BATS
                "WIDEBAND" -> SpectrogramConfig.WIDEBAND
                else -> SpectrogramConfig.BIRDS
            }
            Text(
                text = stringResource(R.string.settings_tipp_langdruck_auf_das_sonogramm),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            SpectrogramSettingsPanel(
                repository = specRepo,
                settings = specSettings,
                config = specConfig,
                showWindow = true,
                onClose = null,
                maxHeight = Dp.Infinity
            )
        }
    }
}
