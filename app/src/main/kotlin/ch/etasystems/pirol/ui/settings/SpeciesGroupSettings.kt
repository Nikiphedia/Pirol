package ch.etasystems.pirol.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ch.etasystems.pirol.R
import ch.etasystems.pirol.ml.CustomSpeciesLists
import ch.etasystems.pirol.ml.GroupSource
import ch.etasystems.pirol.ml.RegionInfo
import ch.etasystems.pirol.ml.SpeciesGroupSelection
import ch.etasystems.pirol.ml.TaxonGroup
import ch.etasystems.pirol.ui.AppStrings

/** T232: Anzeigenamen fuer Artgruppen und Quellen (ausserhalb von Composables nutzbar). */
object SpeciesGroupLabels {

    fun name(g: TaxonGroup): String = AppStrings.get(when (g) {
        TaxonGroup.BIRDS -> R.string.group_birds
        TaxonGroup.ORTHOPTERA -> R.string.group_orthoptera
        TaxonGroup.AMPHIBIANS -> R.string.group_amphibians
        TaxonGroup.MAMMALS -> R.string.group_mammals
        TaxonGroup.OTHER -> R.string.group_other
    })

    fun letters(): Map<TaxonGroup, String> = mapOf(
        TaxonGroup.BIRDS to AppStrings.get(R.string.group_letter_birds),
        TaxonGroup.ORTHOPTERA to AppStrings.get(R.string.group_letter_orthoptera),
        TaxonGroup.AMPHIBIANS to AppStrings.get(R.string.group_letter_amphibians),
        TaxonGroup.MAMMALS to AppStrings.get(R.string.group_letter_mammals),
        TaxonGroup.OTHER to AppStrings.get(R.string.group_letter_other)
    )

    /** Kurzname einer Quelle ("–", Alle, Auto, CH, CH+, EU, Name der eigenen Liste) */
    fun source(src: String): String = when (src) {
        GroupSource.OFF -> "–"
        GroupSource.ALL -> AppStrings.get(R.string.common_all)
        GroupSource.AUTO -> AppStrings.get(R.string.live_region_auto)
        "ch_breeding", "ch_orthoptera_anura" -> "CH"
        "ch_all" -> "CH+"
        "central_europe" -> "EU"
        else -> src.removePrefix(CustomSpeciesLists.ID_PREFIX)
    }

    /** Kurzanzeige, z.B. "V:Auto H:CH A:CH S:–" */
    fun short(sel: SpeciesGroupSelection): String = SpeciesGroupSelection.shortLabel(sel, letters(), ::source)
}

/**
 * T232: Auswahl je Artgruppe: Aus / Alle / Auto / Liste (Dropdown). "Andere" nur Aus / Alle.
 * [regions] = Asset-Regionen und eigene Listen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColumnScope.SpeciesGroupSettings(
    selection: SpeciesGroupSelection,
    regions: List<RegionInfo>,
    onChange: (SpeciesGroupSelection) -> Unit
) {
    Text(stringResource(R.string.settings_species_groups), style = MaterialTheme.typography.bodyMedium)
    Text(stringResource(R.string.settings_species_groups_hint), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    val lists = regions.filter { it.id != GroupSource.ALL }
    TaxonGroup.entries.forEach { g ->
        key(g) {
            val src = selection.sourceOf(g)
            fun set(v: String) = onChange(selection.with(g, v))
            Spacer(modifier = Modifier.height(8.dp))
            Text(SpeciesGroupLabels.name(g), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = src == GroupSource.OFF, onClick = { set(GroupSource.OFF) },
                    label = { Text(stringResource(R.string.common_off)) })
                FilterChip(selected = src == GroupSource.ALL, onClick = { set(GroupSource.ALL) },
                    label = { Text(stringResource(R.string.common_all)) })
                if (g != TaxonGroup.OTHER) {
                    FilterChip(selected = src == GroupSource.AUTO, onClick = { set(GroupSource.AUTO) },
                        label = { Text(stringResource(R.string.live_region_auto)) })
                    var open by remember { mutableStateOf(false) }
                    Box {
                        val isList = GroupSource.isList(src)
                        val listName = lists.firstOrNull { it.id == src }?.nameDe ?: SpeciesGroupLabels.source(src)
                        FilterChip(
                            selected = isList,
                            onClick = { open = true },
                            label = {
                                Text(if (isList) stringResource(R.string.group_source_list) + ": " + listName
                                else stringResource(R.string.group_source_list) + " ▾")
                            }
                        )
                        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                            lists.forEach { r ->
                                DropdownMenuItem(
                                    text = {
                                        Text(if (r.isCustom) r.nameDe + " (" + stringResource(R.string.settings_eigene_liste) + ")"
                                        else r.nameDe)
                                    },
                                    onClick = { open = false; set(r.id) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
