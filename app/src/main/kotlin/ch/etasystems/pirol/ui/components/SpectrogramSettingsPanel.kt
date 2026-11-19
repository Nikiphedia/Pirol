package ch.etasystems.pirol.ui.components

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
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
 * @param config bestimmt die Grenzen des Frequenz-Reglers
 * @param onClose Schliessen-Aktion (null = kein "Fertig"-Knopf, z.B. eingebettet in den Settings)
 * @param showWindow Zeitfenster-Regler nur im Live-Tab (Analyse zeigt immer die ganze Session)
 */
@Composable
fun SpectrogramSettingsPanel(
    repository: SpectrogramSettingsRepository,
    settings: SpectrogramDisplaySettings,
    config: SpectrogramConfig,
    showWindow: Boolean,
    onClose: (() -> Unit)?,
    modifier: Modifier = Modifier,
    maxHeight: Dp = 300.dp
) {
    val cfgMax = if (config.fMax <= 0f) 24000f else config.fMax
    val cfgMin = config.fMin
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    SpectrogramPreset.entries.forEach { preset ->
                        FilterChip(
                            selected = activePreset == preset,
                            onClick = { repository.applyPreset(preset) },
                            label = { Text(stringResource(preset.labelRes)) }
                        )
                    }
                }
                if (onClose != null) TextButton(onClick = onClose) { Text(stringResource(R.string.ui_fertig)) }
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
                    checked = settings.autoContrast,
                    onCheckedChange = { v -> repository.update { it.copy(autoContrast = v) } }
                )
            }
            Text(
                text = if (settings.autoContrast) stringResource(R.string.ui_db_schwelle_automatisch_aus_dem)
                else stringResource(R.string.ui_db_schwelle_bis_db, settings.minDb.roundToInt(), settings.maxDb.roundToInt()),
                style = MaterialTheme.typography.bodySmall
            )
            RangeSlider(
                value = settings.minDb..settings.maxDb,
                onValueChange = { r ->
                    val lo = r.start
                    val hi = if (r.endInclusive - lo < MIN_DISPLAY_SPAN_DB) lo + MIN_DISPLAY_SPAN_DB else r.endInclusive
                    repository.update { it.copy(minDb = lo, maxDb = hi) }
                },
                enabled = !settings.autoContrast,
                valueRange = -100f..10f
            )

            Text(
                AppStrings.get(R.string.ui_intensitaet_gamma_kleiner_leise_hell, "%.2f".format(settings.gamma)),
                style = MaterialTheme.typography.bodySmall
            )
            Slider(
                value = settings.gamma,
                onValueChange = { v -> repository.update { it.copy(gamma = v) } },
                valueRange = 0.3f..1f
            )

            Text(
                stringResource(
                    R.string.ui_helligkeits_deckel,
                    if (settings.ceilingDb >= 0f) stringResource(R.string.ui_deckel_aus)
                    else stringResource(R.string.ui_deckel_db, settings.ceilingDb.roundToInt())
                ),
                style = MaterialTheme.typography.bodySmall
            )
            Slider(
                value = settings.ceilingDb,
                onValueChange = { v -> repository.update { it.copy(ceilingDb = v) } },
                valueRange = -50f..0f
            )

            Text(stringResource(R.string.ui_farbpalette), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SpectrogramPalette.entries.forEach { p ->
                    FilterChip(
                        selected = settings.palette == p,
                        onClick = { repository.update { it.copy(palette = p) } },
                        label = { Text(stringResource(p.labelRes())) }
                    )
                }
            }

            // Frequenzbereich in kHz, begrenzt auf den Bereich der aktiven SpectrogramConfig
            val fLo = settings.freqMinHz.coerceIn(cfgMin, cfgMax - 500f)
            val fHi = settings.freqMaxHz.coerceIn(fLo + 500f, cfgMax)
            Text(
                AppStrings.get(R.string.ui_frequenz_bis_khz, "%.1f".format(fLo / 1000f), "%.1f".format(fHi / 1000f)),
                style = MaterialTheme.typography.bodySmall
            )
            RangeSlider(
                value = fLo..fHi,
                onValueChange = { r ->
                    val lo = r.start
                    val hi = if (r.endInclusive - lo < 500f) lo + 500f else r.endInclusive
                    repository.update { it.copy(freqMinHz = lo, freqMaxHz = hi) }
                },
                valueRange = cfgMin..cfgMax
            )

            if (showWindow) {
                Text(
                    AppStrings.get(R.string.ui_zeitfenster_s, settings.windowSec.roundToInt()),
                    style = MaterialTheme.typography.bodySmall
                )
                Slider(
                    value = settings.windowSec,
                    onValueChange = { v -> repository.update { it.copy(windowSec = v) } },
                    valueRange = 5f..20f
                )
            }
        }
    }
}
