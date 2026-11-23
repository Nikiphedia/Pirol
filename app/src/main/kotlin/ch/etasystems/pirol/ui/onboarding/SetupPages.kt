package ch.etasystems.pirol.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import ch.etasystems.pirol.R
import ch.etasystems.pirol.data.GPS_MODE_ON_DETECTION
import ch.etasystems.pirol.ui.AppLanguage
import ch.etasystems.pirol.ui.AppStrings

/** T226: Seiten des Einrichtungsassistenten (ohne Zustand; OnboardingScreen haelt ihn). */

@Composable
private fun PageTitle(titleRes: Int, hintRes: Int? = null) {
    Text(stringResource(titleRes), style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(12.dp))
    if (hintRes != null) {
        Text(stringResource(hintRes), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(16.dp))
    }
}

/** Auswahlzeile (Radio oder Checkbox), mind. 56 dp hoch */
@Composable
private fun OptionRow(selected: Boolean, multi: Boolean, label: String, hint: String? = null, onClick: () -> Unit) {
    val base = Modifier.fillMaxWidth().heightIn(min = 56.dp)
    val mod = if (multi) base.toggleable(value = selected, onValueChange = { onClick() }, role = Role.Checkbox)
    else base.selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
    Row(modifier = mod.padding(vertical = 8.dp, horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (multi) Checkbox(checked = selected, onCheckedChange = null)
        else RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (hint != null) {
                Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun SetupLanguagePage(currentTag: String, onSelect: (String) -> Unit) {
    PageTitle(R.string.setup_lang_title, R.string.setup_lang_hint)
    Column(Modifier.selectableGroup()) {
        // T228: erste Option = Systemsprache (folgt der Geraetesprache)
        OptionRow(currentTag == AppLanguage.SYSTEM_TAG, false, stringResource(R.string.settings_systemsprache)) {
            onSelect(AppLanguage.SYSTEM_TAG)
        }
        AppLanguage.OPTIONS.forEach { (tag, name) ->
            OptionRow(selected = currentTag == tag, multi = false, label = name) { onSelect(tag) }
        }
    }
}

@Composable
fun SetupLevelPage(expert: Boolean, onSelect: (Boolean) -> Unit) {
    PageTitle(R.string.setup_level_title)
    Column(Modifier.selectableGroup()) {
        OptionRow(!expert, false, stringResource(R.string.setup_level_laie), stringResource(R.string.setup_level_laie_hint)) { onSelect(false) }
        OptionRow(expert, false, stringResource(R.string.setup_level_expert), stringResource(R.string.setup_level_expert_hint)) { onSelect(true) }
    }
}

@Composable
fun SetupPurposePage(selected: Set<SetupPurpose>, onToggle: (SetupPurpose) -> Unit) {
    PageTitle(R.string.setup_purpose_title, R.string.setup_purpose_hint)
    SetupPurpose.entries.forEach { p ->
        val hint = if (p == SetupPurpose.MONITORING) stringResource(R.string.setup_monitoring_hint) else null
        OptionRow(p in selected, true, stringResource(purposeLabel(p)), hint) { onToggle(p) }
    }
}

private fun purposeLabel(p: SetupPurpose): Int = when (p) {
    SetupPurpose.FIELD_BIRDS -> R.string.setup_purpose_field
    SetupPurpose.ORTHOPTERA_AMPHIBIANS -> R.string.setup_purpose_ortho
    SetupPurpose.MONITORING -> R.string.setup_purpose_monitoring
    SetupPurpose.RESEARCH -> R.string.setup_purpose_research
}

private fun listLabel(l: SetupList): Int = when (l) {
    SetupList.AUTO -> R.string.setup_list_auto
    SetupList.SWITZERLAND -> R.string.setup_list_ch
    SetupList.REGION -> R.string.setup_list_region
    SetupList.CUSTOM -> R.string.setup_list_custom
}

@Composable
fun SetupListPage(selected: SetupList, showOrthoSource: Boolean, onSelect: (SetupList) -> Unit) {
    PageTitle(R.string.setup_list_title, R.string.setup_list_hint)
    Column(Modifier.selectableGroup()) {
        SetupList.entries.forEach { l ->
            OptionRow(l == selected, false, stringResource(listLabel(l))) { onSelect(l) }
        }
    }
    // T228: Quellenangabe der mitgelieferten Heuschrecken-/Amphibienliste (CC BY)
    if (showOrthoSource) {  // T232: Liste gilt unabhaengig von der Vogel-Listenwahl
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.setup_list_ortho_note), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.settings_info_lists), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Experten-Seite: Schwelle, Ueberlappung, Zweitmodell feinjustieren (Startwerte = Profil) */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SetupExpertPage(
    threshold: Float, overlap: Int, secondary: Boolean,
    secondaryModels: List<Pair<String, String>>, secondaryChosen: String?,
    onThreshold: (Float) -> Unit, onOverlap: (Int) -> Unit, onSecondary: (Boolean) -> Unit,
    onSecondaryModel: (String) -> Unit
) {
    val secondaryAvailable = secondaryModels.isNotEmpty()
    PageTitle(R.string.setup_expert_title)
    Text(stringResource(R.string.setup_expert_threshold, (threshold * 100).toInt()), style = MaterialTheme.typography.bodyLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(0.3f, 0.4f, 0.5f, 0.6f, 0.7f, 0.8f).forEach { v ->
            FilterChip(
                selected = kotlin.math.abs(threshold - v) < 0.001f, onClick = { onThreshold(v) },
                label = { Text("${(v * 100).toInt()} %") }, modifier = Modifier.heightIn(min = 48.dp)
            )
        }
    }
    Spacer(Modifier.height(16.dp))
    Text(stringResource(R.string.setup_expert_overlap, overlap), style = MaterialTheme.typography.bodyLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(0, 50, 67).forEach { v ->
            FilterChip(
                selected = overlap == v, onClick = { onOverlap(v) },
                label = { Text("$v %") }, modifier = Modifier.heightIn(min = 48.dp)
            )
        }
    }
    Spacer(Modifier.height(16.dp))
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.setup_expert_secondary), style = MaterialTheme.typography.bodyLarge)
            if (!secondaryAvailable) {
                Text(stringResource(R.string.setup_expert_secondary_none), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = secondary && secondaryAvailable, enabled = secondaryAvailable, onCheckedChange = onSecondary)
    }
    // T228: Auswahl unter den installierten, nicht-primaeren Modellen
    if (secondary && secondaryAvailable) {
        Text(stringResource(R.string.setup_expert_secondary_pick), style = MaterialTheme.typography.bodyMedium)
        Column(Modifier.selectableGroup()) {
            secondaryModels.forEach { (file, name) ->
                OptionRow(file == secondaryChosen, false, name) { onSecondaryModel(file) }
            }
        }
    }
}

/** Lesbare Zusammenfassung aller Werte, die der Assistent setzt */
@Composable
fun SetupSummaryPage(choices: SetupChoices, s: SetupSettings, modelName: String?) {
    PageTitle(R.string.setup_summary_title)
    val on = stringResource(R.string.setup_on)
    val off = stringResource(R.string.setup_off)
    val lines = buildList {
        add(AppStrings.get(R.string.setup_sum_level,
            AppStrings.get(if (choices.expert) R.string.setup_level_expert else R.string.setup_level_laie)))
        add(AppStrings.get(R.string.setup_sum_purpose,
            choices.purposes.sortedBy { it.ordinal }.joinToString(", ") { AppStrings.get(purposeLabel(it)) }))
        add(AppStrings.get(R.string.setup_sum_list, AppStrings.get(listLabel(choices.list))))
        // T232: Quelle je Artgruppe (ohne Voegel, die folgen der Listenwahl)
        add(AppStrings.get(R.string.setup_sum_groups, s.groupSources.entries.joinToString(", ") { (g, src) ->
            ch.etasystems.pirol.ui.settings.SpeciesGroupLabels.name(g) + " " + ch.etasystems.pirol.ui.settings.SpeciesGroupLabels.source(src)
        }))
        add(AppStrings.get(R.string.setup_sum_model, modelName ?: AppStrings.get(R.string.setup_sum_model_none)))
        add(AppStrings.get(R.string.setup_sum_threshold, (s.threshold * 100).toInt(), (s.alternativesFloor * 100).toInt()))
        add(AppStrings.get(R.string.setup_sum_overlap, s.overlapPercent))
        add(AppStrings.get(R.string.setup_sum_secondary, if (s.secondaryModelFileName != null) on else off))
        val extras = buildList {
            if (s.geoYearly) add(AppStrings.get(R.string.setup_extra_yearly))
            s.freqMaxHz?.let { add(AppStrings.get(R.string.setup_extra_freq, (it / 1000).toInt())) }
            if (s.gpsMode == GPS_MODE_ON_DETECTION) add(AppStrings.get(R.string.setup_extra_gps))
            if (s.trackRecording == true) add(AppStrings.get(R.string.setup_extra_track))
            s.maxRecordingMinutes?.let { add(AppStrings.get(R.string.setup_extra_segments, it)) }
            s.compactCard?.let { add(AppStrings.get(if (it) R.string.setup_extra_compact else R.string.setup_extra_full)) }
        }
        if (extras.isNotEmpty()) add(AppStrings.get(R.string.setup_sum_extras, extras.joinToString(", ")))
        if (s.secondaryMissing) add(AppStrings.get(R.string.setup_sum_secondary_missing))
        if (SetupPurpose.MONITORING in choices.purposes) add(AppStrings.get(R.string.setup_monitoring_hint))
    }
    lines.forEach {
        Text(it, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 4.dp))
    }
}

/** Zurueck / Weiter (bzw. Fertig) am Seitenende, Tap-Targets >= 48 dp */
@Composable
fun SetupNavRow(onBack: (() -> Unit)?, nextLabel: String, onNext: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.common_back)) }
        } else Spacer(Modifier.width(1.dp))
        Button(onClick = onNext, modifier = Modifier.heightIn(min = 48.dp)) { Text(nextLabel) }
    }
}
