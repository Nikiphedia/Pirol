package ch.etasystems.pirol.ui.components

import ch.etasystems.pirol.ui.help.helpTopic

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ch.etasystems.pirol.audio.dsp.SpectrogramConfig
import ch.etasystems.pirol.data.SpectrogramSettingsRepository
import kotlin.math.roundToInt
import ch.etasystems.pirol.R
import androidx.compose.ui.res.stringResource
import ch.etasystems.pirol.ui.AppStrings

/**
 * T107: Langdruck auf das Sonogramm. Tippen bleibt unberuehrt (andere Gesten werden nicht konsumiert).
 */
fun Modifier.onSpectrogramLongPress(onLongPress: () -> Unit): Modifier =
    pointerInput(Unit) {
        detectTapGestures(onLongPress = { onLongPress() })
    }

/**
 * T107: Einstellungs-Panel, wird UNTER dem Sonogramm eingeblendet (kein Dialog), damit jede
 * Aenderung live im Bild sichtbar bleibt. Alle Aenderungen gehen ueber das gemeinsame Repository.
 *
 * @param config bestimmt die Grenzen des Frequenz-Reglers (gemeinsame Regler, T199)
 * @param onClose Schliessen-Aktion (null = kein "Fertig"-Knopf, z.B. eingebettet in den Settings)
 * @param showWindow Zeitfenster-Regler nur im Live-Tab (Analyse zeigt immer die ganze Session)
 * @param showDisplayMode T193: Umschalter Sonogramm/Spektrum/Oszillogramm nur im Live-Tab
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SpectrogramSettingsPanel(
    repository: SpectrogramSettingsRepository,
    settings: SpectrogramDisplaySettings,
    config: SpectrogramConfig,
    showWindow: Boolean,
    showDisplayMode: Boolean = false,
    onClose: (() -> Unit)?,
    modifier: Modifier = Modifier,
    maxHeight: Dp = 300.dp
) {
    val activePreset = settings.matchingPreset()

    // Eigenes Scrollen nur mit endlicher Hoehe. Eingebettet in den (selbst scrollenden) Settings-Screen
    // wuerde verticalScroll mit unendlicher Hoehe gemessen → IllegalStateException (Absturz).
    val scrollState = rememberScrollState()
    val scrollModifier = if (maxHeight == Dp.Infinity) Modifier
        else Modifier.heightIn(max = maxHeight).verticalScroll(scrollState)
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = scrollModifier
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            // T177: "Fertig" in eigener Zeile oben rechts, Presets als umbrechende Kacheln
            // (auf schmalen Telefonen wurde "Fertig" neben der Preset-Zeile zerdrueckt)
            if (onClose != null) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onClose) { Text(stringResource(R.string.ui_fertig)) }
                }
            }
            if (showDisplayMode) LiveDisplayModeChips(repository, settings)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().helpTopic(R.string.help_display_presets, "3")
            ) {
                SpectrogramPreset.entries.forEach { preset ->
                    FilterChip(
                        selected = activePreset == preset,
                        onClick = { repository.applyPreset(preset) },
                        label = { Text(stringResource(preset.labelRes)) }
                    )
                }
            }

            // Helligkeit/Kontrast: Auto-Kontrast + dB-Schwellen
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    AppStrings.get(R.string.ui_auto_kontrast),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    modifier = Modifier.helpTopic(R.string.help_display_auto_contrast, "3"),
                    checked = settings.autoContrast,
                    onCheckedChange = { v -> repository.update { it.copy(autoContrast = v) } }
                )
            }
            // T199: Frequenz, dB-Bereich (dBFS) und Palette gemeinsam mit Spektrum/Oszillogramm
            SharedDisplayControls(repository, settings, config)

            Text(
                AppStrings.get(R.string.ui_intensitaet_gamma_kleiner_leise_hell, "%.2f".format(settings.gamma)),
                style = MaterialTheme.typography.bodySmall
            )
            Slider(
                modifier = Modifier.helpTopic(R.string.help_display_gamma, "3"),
                value = settings.gamma,
                onValueChange = { v -> repository.update { it.copy(gamma = v) } },
                valueRange = 0.3f..1f
            )

            Text(
                stringResource(
                    R.string.ui_helligkeits_deckel,
                    if (settings.ceilingDb >= 0f) stringResource(R.string.ui_deckel_aus)
                    else stringResource(R.string.ui_deckel_dbfs, settings.ceilingDb.roundToInt())
                ),
                style = MaterialTheme.typography.bodySmall
            )
            Slider(
                modifier = Modifier.helpTopic(R.string.help_display_ceiling, "3"),
                value = settings.ceilingDb,
                onValueChange = { v -> repository.update { it.copy(ceilingDb = v) } },
                valueRange = -80f..0f  // T199: dBFS
            )

            if (showWindow) {
                Text(
                    AppStrings.get(R.string.ui_zeitfenster_s, settings.windowSec.roundToInt()),
                    style = MaterialTheme.typography.bodySmall
                )
                Slider(
                    modifier = Modifier.helpTopic(R.string.help_display_window, "3"),
                    value = settings.windowSec,
                    onValueChange = { v -> repository.update { it.copy(windowSec = v) } },
                    valueRange = 5f..30f
                )
            }
        }
    }
}

/** T193/T198: Moduswahl Sonogramm/Spektrum/Oszillogramm (auch im Spektrum-Panel). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LiveDisplayModeChips(repository: SpectrogramSettingsRepository, settings: SpectrogramDisplaySettings) {
    Text(stringResource(R.string.display_mode_title), style = MaterialTheme.typography.bodySmall)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().helpTopic(R.string.help_display_mode, "3")
    ) {
        LiveDisplayMode.entries.forEach { m ->
            FilterChip(
                selected = settings.displayMode == m,
                onClick = { repository.update { it.copy(displayMode = m) } },
                label = { Text(stringResource(m.labelRes)) }
            )
        }
    }
}
