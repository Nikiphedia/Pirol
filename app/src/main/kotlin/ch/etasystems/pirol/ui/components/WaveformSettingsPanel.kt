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
                TextButton(onClick = onClose) { Text(stringResource(R.string.ui_fertig)) }
            }
            LiveDisplayModeChips(repository, displaySettings)

            SharedDisplayControls(repository, displaySettings, config)
            Text(
                stringResource(R.string.waveform_band_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(stringResource(R.string.waveform_axis), style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WaveformAxis.entries.forEach { a ->
                    FilterChip(
                        selected = settings.axis == a,
                        onClick = { repository.updateWaveform { it.copy(axis = a) } },
                        label = { Text(stringResource(a.labelRes)) }
                    )
                }
            }

            // Amplitudenskala gilt fuer die lineare Achse (dBFS-Achse nutzt den gemeinsamen dB-Bereich)
            Text(stringResource(R.string.waveform_amplitude), style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WaveformAmplitudeMode.entries.forEach { m ->
                    val label = if (m == WaveformAmplitudeMode.MAX_LEVEL) {
                        stringResource(
                            m.labelRes,
                            "±" + amplitudeLabel(waveformFullScale(m, 0f, displaySettings.maxDb))
                        )
                    } else stringResource(m.labelRes)
                    FilterChip(
                        selected = settings.amplitudeMode == m,
                        enabled = settings.axis == WaveformAxis.LINEAR,
                        onClick = { repository.updateWaveform { it.copy(amplitudeMode = m) } },
                        label = { Text(label) }
                    )
                }
            }
        }
    }
}
