package ch.etasystems.pirol.ui.components

import ch.etasystems.pirol.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.text.style.TextAlign
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
    windowFrames: Int? = null,
    lightBackground: Boolean = false,
    content: @Composable BoxScope.() -> Unit
) {
    // Sekunden pro Frame = hopSize / sampleRate
    val secondsPerFrame = config.hopSize.toFloat() / sampleRate

    Box(modifier = Modifier.fillMaxSize()) {
        // Sonogramm-Canvas als Inhalt
        content()

        // Frequenzachse (links)
        FrequencyAxis(
            config = config,
            lightBackground = lightBackground,
            modifier = Modifier
                .fillMaxHeight()
                .align(Alignment.CenterStart)
        )

        // Zeitachse (unten)
        TimeAxis(
            spectrogramState = spectrogramState,
            secondsPerFrame = secondsPerFrame,
            windowFrames = windowFrames,
            lightBackground = lightBackground,
            modifier = Modifier
                .fillMaxWidth()
                .height(18.dp)
                .align(Alignment.BottomCenter)
        )
    }
}

/**
 * T144: Frequenzachse links mit festen Marken (1/2/4/8/12 kHz, nur im sichtbaren Bereich).
 * Eigenstaendig nutzbar (Analyse-Tab), im [SpectrogramOverlay] zusammen mit der Zeitachse.
 *
 * @param config Anzeige-Config (fMin/fMax = sichtbarer Bereich, siehe `forDisplay`)
 * @param lightBackground true bei heller Palette: dunkle Schrift auf hellem Streifen
 */
@Composable
fun FrequencyAxis(
    config: SpectrogramConfig,
    lightBackground: Boolean,
    modifier: Modifier = Modifier
) {
    val fMin = config.fMin
    val fMax = if (config.fMax <= 0f) 24000f else config.fMax
    val linear = config.linearFreq  // T294: Ultraschall-Achse linear
    val ticks = remember(fMin, fMax, linear) { computeFrequencyTicks(fMin, fMax, linear) }
    val textColor = if (lightBackground) Color.Black else Color.White
    val stripColor = if (lightBackground) Color.White.copy(alpha = 0.55f) else Color.Black.copy(alpha = 0.4f)

    BoxWithConstraints(modifier = modifier.width(40.dp).background(stripColor)) {
        val heightDp = maxHeight
        for (tick in ticks) {
            val yDp = heightDp * (1f - frequencyFraction(tick.hz, fMin, fMax, linear))
            // Kleine Linie an der Tick-Position (rechter Rand der Achse)
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(y = yDp)
                    .width(6.dp)
                    .height(1.dp)
                    .background(textColor.copy(alpha = 0.9f))
            )
            // Label auf Hoehe der Linie, innerhalb der Achsenhoehe gehalten
            val labelY = (yDp - 6.dp).coerceIn(0.dp, (heightDp - 12.dp).coerceAtLeast(0.dp))
            Text(
                text = tick.label,
                fontSize = 9.sp,
                color = textColor.copy(alpha = 0.9f),
                textAlign = TextAlign.End,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(y = labelY)
                    .padding(end = 8.dp)
            )
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
    windowFrames: Int? = null,
    lightBackground: Boolean = false,
    modifier: Modifier = Modifier
) {
    val textColor = (if (lightBackground) Color.Black else Color.White).copy(alpha = 0.85f)
    // T107: bei festem Zeitfenster ist die Achse immer das volle Fenster.
    // T160: frameVersion nur ohne festes Fenster lesen — sonst Recomposition bei jedem Frame.
    val totalFrames = windowFrames ?: run {
        @Suppress("UNUSED_VARIABLE")
        val version = spectrogramState.frameVersion
        spectrogramState.availableFrames
    }
    val totalSeconds = totalFrames * secondsPerFrame

    Box(
        modifier = modifier.background(
            if (lightBackground) Color.White.copy(alpha = 0.55f) else Color.Black.copy(alpha = 0.4f)
        )
    ) {
        // "Jetzt" rechts
        Text(
            text = stringResource(R.string.ui_jetzt),
            fontSize = 9.sp,
            color = textColor,
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
                color = textColor,
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
                color = textColor,
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}

/** T144: Feste Frequenz-Marken in Hz (Vogel-Standard). T200: 20k fuer den Bereich bis Nyquist. */
internal val FIXED_FREQUENCY_TICKS_HZ = floatArrayOf(1000f, 2000f, 4000f, 8000f, 12000f, 20000f)

/** T144: Marken im sichtbaren Bereich [fMin, fMax]. T294: lineare Achse mit gleichmaessigen Schritten. */
internal fun computeFrequencyTicks(fMin: Float, fMax: Float, linear: Boolean = false): List<FrequencyTick> {
    if (linear) {
        val step = linearTickStepHz(fMax - fMin)
        val out = ArrayList<FrequencyTick>()
        var k = kotlin.math.ceil(fMin / step).toInt().coerceAtLeast(1)
        while (k * step <= fMax) {
            val hz = k * step
            out += FrequencyTick(hz = hz, label = "${(hz / 1000f).toInt()}k")
            k++
        }
        return out
    }
    return FIXED_FREQUENCY_TICKS_HZ
        .filter { it >= fMin && it <= fMax }
        .map { FrequencyTick(hz = it, label = "${(it / 1000f).toInt()}k") }
}

/** T294: Schritt der linearen Achse: kleinster Schritt mit hoechstens 8 Marken (mindestens 1 kHz). */
internal fun linearTickStepHz(spanHz: Float): Float {
    val steps = floatArrayOf(1000f, 2000f, 5000f, 10000f, 20000f, 25000f, 50000f, 100000f)
    return steps.firstOrNull { spanHz / it <= 8f } ?: steps.last()
}

/**
 * T144: Position einer Frequenz auf der Mel-Skala als Anteil [0, 1] (0 = fMin unten, 1 = fMax oben).
 * T294: [linear] = lineare Hz-Achse (Ultraschall).
 */
internal fun frequencyFraction(hz: Float, fMin: Float, fMax: Float, linear: Boolean = false): Float {
    if (linear) return if (fMax <= fMin) 0f else ((hz - fMin) / (fMax - fMin)).coerceIn(0f, 1f)
    val melMin = MelFilterbank.hzToMel(fMin)
    val melMax = MelFilterbank.hzToMel(fMax)
    if (melMax <= melMin) return 0f
    return ((MelFilterbank.hzToMel(hz) - melMin) / (melMax - melMin)).coerceIn(0f, 1f)
}

internal data class FrequencyTick(
    val hz: Float,
    val label: String
)
