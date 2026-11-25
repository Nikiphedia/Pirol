package ch.etasystems.pirol.ui.components

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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ch.etasystems.pirol.R
import ch.etasystems.pirol.ui.help.helpTopic
import ch.etasystems.pirol.audio.dsp.SpectrogramConfig
import ch.etasystems.pirol.audio.dsp.SpectrumAnalyzer
import ch.etasystems.pirol.data.SpectrogramSettingsRepository
import java.util.Locale

/**
 * T198: Panel des Live-Spektrums (Langdruck im Spektrum-Modus), unter der Anzeige eingeblendet.
 * Oben die Moduswahl, damit man zum Sonogramm zurueckkommt. Freeze liegt in der Anzeige selbst.
 * T199: Frequenz-/dB-Bereich und Palette ueber [SharedDisplayControls] (gemeinsam), [config] = aktive
 * Sonogramm-Config (Grenzen des Frequenz-Reglers).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SpectrumSettingsPanel(
    repository: SpectrogramSettingsRepository,
    displaySettings: SpectrogramDisplaySettings,
    settings: SpectrumSettings,
    config: SpectrogramConfig,
    sampleRate: Int,
    onResetPeak: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    maxHeight: Dp = 300.dp
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .heightIn(max = maxHeight)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onClose, modifier = Modifier.helpTopic(R.string.help_display_panel_done, "3")) { Text(stringResource(R.string.ui_fertig)) }
            }
            LiveDisplayModeChips(repository, displaySettings)

            // T199: Frequenz, dB-Bereich (dBFS) und Palette gemeinsam mit Sonogramm/Oszillogramm
            SharedDisplayControls(repository, displaySettings, config)

            // T275: Gamma (wie im Sonogramm) wirkt auch im Spektrum
            Text(
                stringResource(
                    R.string.ui_intensitaet_gamma_kleiner_leise_hell,
                    "%.2f".format(Locale.US, displaySettings.gamma)
                ),
                style = MaterialTheme.typography.bodySmall
            )
            Slider(
                modifier = Modifier.helpTopic(R.string.help_display_gamma, "3"),
                value = displaySettings.gamma,
                onValueChange = { v -> repository.update { it.copy(gamma = v) } },
                valueRange = 0.3f..1f
            )

            // T275: Frequenzachse Mel / linear
            Text(stringResource(R.string.spectrum_scale), style = MaterialTheme.typography.bodySmall)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().helpTopic(R.string.help_spectrum_scale, "3")
            ) {
                SpectrumScale.entries.forEach { sc ->
                    FilterChip(
                        selected = settings.scale == sc,
                        onClick = { repository.updateSpectrum { it.copy(scale = sc) } },
                        label = { Text(stringResource(sc.labelRes)) }
                    )
                }
            }

            // FFT-Groesse mit Frequenz-Aufloesung
            Text(
                stringResource(
                    R.string.spectrum_fft_size,
                    "%.1f".format(Locale.US, sampleRate.toFloat() / settings.fftSize)
                ),
                style = MaterialTheme.typography.bodySmall
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().helpTopic(R.string.help_spectrum_fft, "3")
            ) {
                SpectrumAnalyzer.FFT_SIZES.forEach { n ->
                    FilterChip(
                        selected = settings.fftSize == n,
                        onClick = { repository.updateSpectrum { it.copy(fftSize = n) } },
                        label = { Text("$n") }
                    )
                }
            }

            Text(stringResource(R.string.spectrum_averaging), style = MaterialTheme.typography.bodySmall)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().helpTopic(R.string.help_spectrum_averaging, "3")
            ) {
                SpectrumAveraging.entries.forEach { a ->
                    FilterChip(
                        selected = settings.averaging == a,
                        onClick = { repository.updateSpectrum { it.copy(averaging = a) } },
                        label = { Text(stringResource(a.labelRes)) }
                    )
                }
            }

            // T201: rollende Peak-Spuren der letzten N Sekunden
            Text(stringResource(R.string.spectrum_traces), style = MaterialTheme.typography.bodySmall)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().helpTopic(R.string.help_spectrum_traces, "3")
            ) {
                SPECTRUM_TRACE_OPTIONS.forEach { t ->
                    FilterChip(
                        selected = settings.traceSec == t,
                        onClick = { repository.updateSpectrum { it.copy(traceSec = t) } },
                        label = {
                            Text(
                                if (t <= 0f) stringResource(R.string.spectrum_traces_off)
                                else stringResource(R.string.spectrum_traces_seconds, t.toInt())
                            )
                        }
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    stringResource(R.string.spectrum_peak_hold),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(
                    onClick = onResetPeak,
                    enabled = settings.peakHold,
                    modifier = Modifier.helpTopic(R.string.help_spectrum_peak_reset, "3")
                ) {
                    Text(stringResource(R.string.spectrum_peak_reset))
                }
                Switch(
                    modifier = Modifier.helpTopic(R.string.help_spectrum_peak_hold, "3"),
                    checked = settings.peakHold,
                    onCheckedChange = { v -> repository.updateSpectrum { it.copy(peakHold = v) } }
                )
            }
        }
    }
}
