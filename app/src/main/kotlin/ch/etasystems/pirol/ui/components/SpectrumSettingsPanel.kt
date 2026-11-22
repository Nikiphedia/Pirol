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
import androidx.compose.material3.RangeSlider
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
import ch.etasystems.pirol.audio.dsp.SpectrumAnalyzer
import ch.etasystems.pirol.data.SpectrogramSettingsRepository
import java.util.Locale
import kotlin.math.roundToInt

/**
 * T198: Panel des Live-Spektrums (Langdruck im Spektrum-Modus), unter der Anzeige eingeblendet.
 * Oben die Moduswahl, damit man zum Sonogramm zurueckkommt. Freeze liegt in der Anzeige selbst.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SpectrumSettingsPanel(
    repository: SpectrogramSettingsRepository,
    displaySettings: SpectrogramDisplaySettings,
    settings: SpectrumSettings,
    sampleRate: Int,
    onResetPeak: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    maxHeight: Dp = 300.dp
) {
    val nyquist = sampleRate / 2f
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

            // Frequenzbereich (linear, kHz)
            val fLo = settings.freqMinHz.coerceIn(0f, nyquist - SPECTRUM_MIN_SPAN_HZ)
            val fHi = settings.freqMaxHz.coerceIn(fLo + SPECTRUM_MIN_SPAN_HZ, nyquist)
            Text(
                stringResource(
                    R.string.ui_frequenz_bis_khz,
                    "%.1f".format(Locale.US, fLo / 1000f), "%.1f".format(Locale.US, fHi / 1000f)
                ),
                style = MaterialTheme.typography.bodySmall
            )
            RangeSlider(
                value = fLo..fHi,
                onValueChange = { r ->
                    // auf 100 Hz runden, damit die Werte ruhig ablesbar bleiben
                    val lo = (r.start / 100f).roundToInt() * 100f
                    val hi = maxOf((r.endInclusive / 100f).roundToInt() * 100f, lo + SPECTRUM_MIN_SPAN_HZ)
                    repository.updateSpectrum { it.copy(freqMinHz = lo, freqMaxHz = hi) }
                },
                valueRange = 0f..nyquist
            )

            // dB-Bereich (dBFS)
            Text(
                stringResource(R.string.spectrum_db_range, settings.minDb.roundToInt(), settings.maxDb.roundToInt()),
                style = MaterialTheme.typography.bodySmall
            )
            RangeSlider(
                value = settings.minDb..settings.maxDb,
                onValueChange = { r ->
                    val lo = r.start.roundToInt().toFloat()
                    val hi = maxOf(r.endInclusive.roundToInt().toFloat(), lo + SPECTRUM_MIN_SPAN_DB)
                    repository.updateSpectrum { it.copy(minDb = lo, maxDb = hi) }
                },
                valueRange = -140f..0f
            )

            // FFT-Groesse mit Frequenz-Aufloesung
            Text(
                stringResource(
                    R.string.spectrum_fft_size,
                    "%.1f".format(Locale.US, sampleRate.toFloat() / settings.fftSize)
                ),
                style = MaterialTheme.typography.bodySmall
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SpectrumAnalyzer.FFT_SIZES.forEach { n ->
                    FilterChip(
                        selected = settings.fftSize == n,
                        onClick = { repository.updateSpectrum { it.copy(fftSize = n) } },
                        label = { Text("$n") }
                    )
                }
            }

            Text(stringResource(R.string.spectrum_averaging), style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SpectrumAveraging.entries.forEach { a ->
                    FilterChip(
                        selected = settings.averaging == a,
                        onClick = { repository.updateSpectrum { it.copy(averaging = a) } },
                        label = { Text(stringResource(a.labelRes)) }
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
                OutlinedButton(onClick = onResetPeak, enabled = settings.peakHold) {
                    Text(stringResource(R.string.spectrum_peak_reset))
                }
                Switch(
                    checked = settings.peakHold,
                    onCheckedChange = { v -> repository.updateSpectrum { it.copy(peakHold = v) } }
                )
            }
        }
    }
}
