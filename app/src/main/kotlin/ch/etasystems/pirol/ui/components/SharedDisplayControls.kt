package ch.etasystems.pirol.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ch.etasystems.pirol.R
import ch.etasystems.pirol.ui.help.helpTopic
import ch.etasystems.pirol.audio.dsp.SpectrogramConfig
import ch.etasystems.pirol.data.SpectrogramSettingsRepository
import java.util.Locale
import kotlin.math.roundToInt

/** T199: Wertebereich des gemeinsamen dB-Reglers in dBFS. */
const val SHARED_DB_SLIDER_MIN = -140f
const val SHARED_DB_SLIDER_MAX = 0f

/** T199: Mindestbreite des gemeinsamen Frequenzbereichs in Hz. */
const val SHARED_MIN_SPAN_HZ = 500f

/**
 * T199: Sichtbarer Frequenzbereich (Hz) fuer alle Live-Ansichten: Einstellung begrenzt auf den
 * Bereich der aktiven [SpectrogramConfig] (wie das Sonogramm ihn zeigt) und auf Nyquist.
 */
fun sharedFreqRange(settings: SpectrogramDisplaySettings, config: SpectrogramConfig, sampleRate: Int): Pair<Float, Float> {
    val shown = settings.forConfig(config)  // T294: Ultraschall-Bereich bei linearer Config
    val disp = config.forDisplay(shown.freqMinHz, shown.freqMaxHz)
    val nyquist = sampleRate / 2f
    val hi = disp.fMax.coerceAtMost(nyquist)
    val lo = disp.fMin.coerceIn(0f, (hi - 1f).coerceAtLeast(0f))
    return lo to hi
}

/**
 * T199: Neuer dB-Bereich aus dem Regler (ganze dB, Mindestspanne). Setzt Auto-Kontrast aus,
 * damit das Sonogramm dem gemeinsamen Bereich sofort folgt.
 */
fun SpectrogramDisplaySettings.withDbRange(start: Float, end: Float): SpectrogramDisplaySettings {
    val lo = start.roundToInt().toFloat()
    val hi = maxOf(end.roundToInt().toFloat(), lo + MIN_DISPLAY_SPAN_DB)
    return copy(minDb = lo, maxDb = hi, autoContrast = false)
}

/**
 * T199: Gemeinsame Regler fuer Frequenzbereich, Dynamikbereich (dBFS) und Farbmuster.
 * Eingebunden im Sonogramm- und Spektrum-Panel (T200: auch im Oszillogramm-Panel). Alle
 * Aenderungen gehen ueber [SpectrogramSettingsRepository.update] und wirken sofort in allen
 * Ansichten.
 *
 * @param config bestimmt die Grenzen des Frequenz-Reglers
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SharedDisplayControls(
    repository: SpectrogramSettingsRepository,
    settings: SpectrogramDisplaySettings,
    config: SpectrogramConfig
) {
    val cfgMax = if (config.fMax <= 0f) 24000f else config.fMax
    val cfgMin = config.fMin
    val shown = settings.forConfig(config)  // T294: Ultraschall-Session -> eigener Bereich

    // Frequenzbereich in kHz, begrenzt auf den Bereich der aktiven SpectrogramConfig
    val fLo = shown.freqMinHz.coerceIn(cfgMin, cfgMax - SHARED_MIN_SPAN_HZ)
    val fHi = shown.freqMaxHz.coerceIn(fLo + SHARED_MIN_SPAN_HZ, cfgMax)
    Text(
        stringResource(
            R.string.ui_frequenz_bis_khz,
            "%.1f".format(Locale.US, fLo / 1000f), "%.1f".format(Locale.US, fHi / 1000f)
        ),
        style = MaterialTheme.typography.bodySmall
    )
    RangeSlider(
        modifier = Modifier.helpTopic(R.string.help_display_freq_range, "3"),
        value = fLo..fHi,
        onValueChange = { r ->
            val lo = r.start
            val hi = if (r.endInclusive - lo < SHARED_MIN_SPAN_HZ) lo + SHARED_MIN_SPAN_HZ else r.endInclusive
            repository.update { it.withFreqRange(config, lo, hi) }
        },
        valueRange = cfgMin..cfgMax
    )

    // Dynamikbereich in dBFS
    Text(
        stringResource(R.string.spectrum_db_range, settings.minDb.roundToInt(), settings.maxDb.roundToInt()),
        style = MaterialTheme.typography.bodySmall
    )
    if (settings.autoContrast) {
        Text(
            stringResource(R.string.display_db_range_auto_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    RangeSlider(
        modifier = Modifier.helpTopic(R.string.help_display_db_range, "3"),
        value = settings.minDb.coerceIn(SHARED_DB_SLIDER_MIN, SHARED_DB_SLIDER_MAX)..
            settings.maxDb.coerceIn(SHARED_DB_SLIDER_MIN, SHARED_DB_SLIDER_MAX),
        onValueChange = { r -> repository.update { it.withDbRange(r.start, r.endInclusive) } },
        valueRange = SHARED_DB_SLIDER_MIN..SHARED_DB_SLIDER_MAX
    )

    Text(stringResource(R.string.ui_farbpalette), style = MaterialTheme.typography.bodySmall)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().helpTopic(R.string.help_display_palette, "3")
    ) {  // T177: Kacheln, umbrechend
        SpectrogramPalette.entries.forEach { p ->
            FilterChip(
                selected = settings.palette == p,
                onClick = { repository.update { it.copy(palette = p) } },
                label = { Text(stringResource(p.labelRes())) }
            )
        }
    }
}
