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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ch.etasystems.pirol.R
import ch.etasystems.pirol.ui.help.helpTopic
import ch.etasystems.pirol.audio.dsp.SpectrogramConfig
import ch.etasystems.pirol.data.SpectrogramSettingsRepository

/**
 * T200: Panel des Live-Oszillogramms (Langdruck im Oszillogramm-Modus), unter der Anzeige.
 * Oben die Moduswahl, dann die gemeinsamen Regler ([SharedDisplayControls]: Frequenzbereich =
 * Bandpass, dB-Bereich, Farbmuster), darunter Achse und Amplitudenskala. Freeze liegt in der Anzeige.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WaveformSettingsPanel(
    repository: SpectrogramSettingsRepository,
    displaySettings: SpectrogramDisplaySettings,
    settings: WaveformSettings,
    config: SpectrogramConfig,
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

            SharedDisplayControls(repository, displaySettings, config)
            // T290: Zeitbasis = Zeitfenster des Sonogramms (gleicher Wert)
            WindowSecControl(repository, displaySettings)
            Text(
                stringResource(R.string.waveform_band_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(stringResource(R.string.waveform_level), style = MaterialTheme.typography.bodySmall)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().helpTopic(R.string.help_waveform_level, "3")
            ) {
                WaveformLevelMode.entries.forEach { a ->
                    FilterChip(
                        selected = settings.levelMode == a,
                        onClick = { repository.updateWaveform { it.copy(levelMode = a) } },
                        label = { Text(stringResource(a.labelRes)) }
                    )
                }
            }

            // Amplitudenskala gilt nur im Modus %; die dBFS-Modi folgen FFT bzw. Sonogramm
            Text(stringResource(R.string.waveform_amplitude), style = MaterialTheme.typography.bodySmall)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().helpTopic(R.string.help_waveform_amplitude, "3")
            ) {
                WaveformAmplitudeMode.entries.forEach { m ->
                    val label = if (m == WaveformAmplitudeMode.MAX_LEVEL) {
                        stringResource(
                            m.labelRes,
                            "±" + amplitudeLabel(waveformFullScale(m, 0f, displaySettings.maxDb))
                        )
                    } else stringResource(m.labelRes)
                    FilterChip(
                        selected = settings.amplitudeMode == m,
                        enabled = settings.levelMode == WaveformLevelMode.PERCENT,
                        onClick = { repository.updateWaveform { it.copy(amplitudeMode = m) } },
                        label = { Text(label) }
                    )
                }
            }
        }
    }
}
