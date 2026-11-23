package ch.etasystems.pirol.ui.analysis

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import ch.etasystems.pirol.audio.dsp.SpectrogramConfig
import ch.etasystems.pirol.audio.export.ExcerptWav
import ch.etasystems.pirol.ui.components.LiveDisplayMode
import ch.etasystems.pirol.ui.components.SpectrogramDisplaySettings
import ch.etasystems.pirol.ui.components.SpectrumSettings
import ch.etasystems.pirol.ui.components.SpectrumState
import ch.etasystems.pirol.ui.components.SpectrumView
import ch.etasystems.pirol.ui.components.WaveformSettings
import ch.etasystems.pirol.ui.components.WaveformState
import ch.etasystems.pirol.ui.components.WaveformView
import ch.etasystems.pirol.ui.components.sharedFreqRange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * T205: Spektrum bzw. Oszillogramm des sichtbaren Ausschnitts in der Analyse.
 *
 * Dieselben Zeichen-Views und gemeinsamen Einstellungen wie live; die Daten kommen aus dem
 * Ausschnitt-PCM statt aus dem Live-Strom. Gerechnet wird nur der gerade angezeigte Modus (Default-Dispatcher).
 *
 * @param excerptStartSec Start des Ausschnitts auf der Session-Achse
 * @param posSec Abspielposition auf der Session-Achse (null = keine Wiedergabe)
 */
@Composable
internal fun AnalysisExcerptView(
    mode: LiveDisplayMode,
    pcm: ExcerptWav.Pcm?,
    excerptStartSec: Float,
    posSec: Float?,
    settings: SpectrogramDisplaySettings,
    spectrumSettings: SpectrumSettings,
    waveformSettings: WaveformSettings,
    sonoAutoRange: Pair<Float, Float>?,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (pcm == null) return
    val config = SpectrogramConfig.BIRDS.resolved(pcm.sampleRate)
    if (mode == LiveDisplayMode.SPECTRUM) {
        // Ohne Wiedergabe: Mittel + Maximum ueber den ganzen Ausschnitt; bei Wiedergabe: Fenster um die Position
        val state = remember { SpectrumState() }
        val posKey = posSec?.let { (it * 10f).toInt() }
        LaunchedEffect(pcm, spectrumSettings.fftSize, posKey) {
            val (from, to) = spectrumSliceRange(pcm.samples.size, pcm.sampleRate, excerptStartSec, posSec)
            val sp = withContext(Dispatchers.Default) {
                computeExcerptSpectrum(pcm.samples, from, to, spectrumSettings.fftSize)
            } ?: return@LaunchedEffect
            state.setStatic(sp.mean, sp.max, pcm.sampleRate)
        }
        if (state.hasData) {
            SpectrumView(
                spectrumState = state,
                settings = spectrumSettings,
                displaySettings = settings,
                config = config,
                onLongPress = onLongPress,
                modifier = modifier,
                showFreeze = false
            )
        }
    } else if (mode == LiveDisplayMode.WAVEFORM) {
        val (bandLo, bandHi) = sharedFreqRange(settings, config, pcm.sampleRate)
        var wave by remember { mutableStateOf<WaveformState?>(null) }
        LaunchedEffect(pcm, bandLo, bandHi) {
            wave = withContext(Dispatchers.Default) {
                val bin = staticWaveformBinSize(pcm.samples.size)
                WaveformState(maxBins = maxOf(16, pcm.samples.size / bin), binSize = bin).also {
                    it.setBand(bandLo, bandHi)  // gemeinsamer Frequenzbereich = Bandpass wie live
                    it.append(pcm.samples, pcm.sampleRate)
                }
            }
        }
        val ws = wave
        if (ws != null && ws.hasData) {
            // Fenster = alle Bins (+ halbes Bin gegen Rundung beim Abschneiden)
            val windowSec = (ws.size * ws.binSize + ws.binSize / 2f) / pcm.sampleRate
            WaveformView(
                waveformState = ws,
                windowSec = windowSec,
                sampleRate = pcm.sampleRate,
                settings = settings,
                waveformSettings = waveformSettings,
                sonoAutoRange = { sonoAutoRange },
                onLongPress = onLongPress,
                modifier = modifier,
                timeOriginSec = excerptStartSec,
                showFreeze = false
            )
        }
    }
}
