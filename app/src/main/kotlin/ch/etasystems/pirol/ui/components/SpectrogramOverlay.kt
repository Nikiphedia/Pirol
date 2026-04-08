package ch.etasystems.pirol.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.etasystems.pirol.audio.dsp.MelFilterbank
import ch.etasystems.pirol.audio.dsp.SpectrogramConfig

/**
 * Achsen-Overlay fuer SpectrogramCanvas.
 *
 * Zeichnet Frequenz-Labels (links) und Zeit-Labels (unten) ueber das Sonogramm.
 * Halbtransparenter Hintergrund fuer Lesbarkeit.
 *
 * @param config SpectrogramConfig fuer Frequenzbereich
 * @param spectrogramState Fuer Zeitberechnung (Anzahl Frames)
 * @param sampleRate Aktuelle Samplerate fuer Zeitberechnung
 * @param content Innerer Content (typischerweise SpectrogramCanvas)
 */
@Composable
fun SpectrogramOverlay(
    config: SpectrogramConfig,
    spectrogramState: SpectrogramState,
    sampleRate: Int = 48000,
    content: @Composable BoxScope.() -> Unit
) {
    val frequencyTicks = remember(config) {
        computeFrequencyTicks(config)
    }

    // Sekunden pro Frame = hopSize / sampleRate
    val secondsPerFrame = config.hopSize.toFloat() / sampleRate

    Box(modifier = Modifier.fillMaxSize()) {
        // Sonogramm-Canvas als Inhalt
        content()

        // Frequenzachse (links)
        FrequencyAxis(
            ticks = frequencyTicks,
            config = config,
            modifier = Modifier
                .fillMaxHeight()
                .width(40.dp)
                .align(Alignment.CenterStart)
        )

        // Zeitachse (unten)
        TimeAxis(
            spectrogramState = spectrogramState,
            secondsPerFrame = secondsPerFrame,
            modifier = Modifier
                .fillMaxWidth()
                .height(18.dp)
                .align(Alignment.BottomCenter)
        )
    }
}

/**
 * Frequenz-Labels links am Sonogramm.
 */
@Composable
private fun FrequencyAxis(
    ticks: List<FrequencyTick>,
    config: SpectrogramConfig,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    var boxSize = IntSize.Zero

    Box(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.4f))
            .onSizeChanged { boxSize = it }
    ) {
        if (boxSize.height <= 0) return@Box

        val fMin = config.fMin
        val fMax = if (config.fMax <= 0f) 24000f else config.fMax // Fallback Nyquist
        val numBins = config.numMelBins

        for (tick in ticks) {
            // Mel-Position im Bin-Raum berechnen
            val melMin = MelFilterbank.hzToMel(fMin)
            val melMax = MelFilterbank.hzToMel(fMax)
            val melTick = MelFilterbank.hzToMel(tick.hz)

            // Normalisierte Position [0, 1] (0 = fMin unten, 1 = fMax oben)
            val normalizedPos = ((melTick - melMin) / (melMax - melMin)).coerceIn(0f, 1f)

            // Y-Position (invertiert: 0 oben, 1 unten → fMin unten)
            val yFraction = 1f - normalizedPos
            val yPx = yFraction * boxSize.height

            with(density) {
                Text(
                    text = tick.label,
                    fontSize = 9.sp,
                    color = Color.White.copy(alpha = 0.85f),
                    textAlign = TextAlign.End,
                    modifier = Modifier
                        .offset(y = (yPx / density.density).dp - 6.dp)
                        .padding(horizontal = 3.dp)
                        .align(Alignment.TopEnd)
                )
            }
        }
    }
}

/**
 * Zeit-Labels unten am Sonogramm.
 */
@Composable
private fun TimeAxis(
    spectrogramState: SpectrogramState,
    secondsPerFrame: Float,
    modifier: Modifier = Modifier
) {
    // Lese frameVersion fuer Recomposition
    @Suppress("UNUSED_VARIABLE")
    val version = spectrogramState.frameVersion

    val totalFrames = spectrogramState.availableFrames
    val totalSeconds = totalFrames * secondsPerFrame

    Box(
        modifier = modifier.background(Color.Black.copy(alpha = 0.4f))
    ) {
        // "Jetzt" rechts
        Text(
            text = "jetzt",
            fontSize = 9.sp,
            color = Color.White.copy(alpha = 0.85f),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 4.dp)
        )

        // Zeitstufen links
        if (totalSeconds > 1f) {
            val timeLabel = "-${totalSeconds.toInt()}s"
            Text(
                text = timeLabel,
                fontSize = 9.sp,
                color = Color.White.copy(alpha = 0.85f),
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 42.dp) // Platz fuer Frequenzachse
            )
        }

        // Mittlerer Zeitpunkt
        if (totalSeconds > 5f) {
            val midLabel = "-${(totalSeconds / 2).toInt()}s"
            Text(
                text = midLabel,
                fontSize = 9.sp,
                color = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}

/**
 * Berechnet sinnvolle Frequenz-Tick-Positionen basierend auf dem Config-Bereich.
 */
private fun computeFrequencyTicks(config: SpectrogramConfig): List<FrequencyTick> {
    val fMin = config.fMin
    val fMax = if (config.fMax <= 0f) 24000f else config.fMax

    // Kandidaten-Frequenzen (Hz)
    val candidates = listOf(
        62.5f, 125f, 250f, 500f, 1000f, 2000f, 4000f,
        8000f, 16000f, 24000f, 48000f
    )

    // Nur Frequenzen im Bereich [fMin, fMax] behalten, max 5 Ticks
    val ticks = candidates
        .filter { it >= fMin && it <= fMax }
        .map { hz ->
            FrequencyTick(
                hz = hz,
                label = formatHz(hz)
            )
        }

    // Max 5 Ticks: gleichmaessig verteilt auswaehlen
    return if (ticks.size > 5) {
        val step = ticks.size.toFloat() / 5f
        (0 until 5).map { i -> ticks[(i * step).toInt()] }
    } else {
        ticks
    }
}

private fun formatHz(hz: Float): String = when {
    hz >= 1000f -> "${(hz / 1000).toInt()}k"
    hz >= 100f -> "${hz.toInt()}"
    else -> "${"%.0f".format(hz)}"
}

private data class FrequencyTick(
    val hz: Float,
    val label: String
)
