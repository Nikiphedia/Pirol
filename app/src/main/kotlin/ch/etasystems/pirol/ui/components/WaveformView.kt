package ch.etasystems.pirol.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import ch.etasystems.pirol.R

/**
 * T193: Live-Oszillogramm (Wellenform, Amplitude ueber Zeit) im Zeitfenster des Sonogramms.
 *
 * Pro Pixelspalte ein Min/Max-Strich aus dem [WaveformState]. Die Amplitudenachse skaliert
 * automatisch auf den Spitzenwert im Fenster (Stufen 0.01 .. 1.0). Gezeichnet nur in der Draw-Phase.
 *
 * @param windowSec Zeitfenster in Sekunden (wie beim Sonogramm)
 * @param sampleRate Abtastrate fuer die Zeitumrechnung
 */
@Composable
fun WaveformView(
    waveformState: WaveformState,
    windowSec: Float,
    sampleRate: Int,
    settings: SpectrogramDisplaySettings,
    modifier: Modifier = Modifier
) {
    val lightBg = settings.palette.isLight
    val textColor = if (lightBg) Color.Black else Color.White
    val lineColor = remember(settings.palette) { colorForValue(0.85f, settings.palette) }
    val measurer = rememberTextMeasurer()
    val labelStyle = remember(textColor) { TextStyle(fontSize = 9.sp, color = textColor.copy(alpha = 0.85f)) }
    val nowText = stringResource(R.string.ui_jetzt)
    val windowBins = (windowSec * sampleRate / waveformState.binSize).toInt()
        .coerceIn(16, waveformState.maxBins)
    val realWindowSec = windowBins.toFloat() * waveformState.binSize / sampleRate

    val tick = rememberDrawTick(waveformState) { waveformState.version }
    // Arbeitspuffer einmal anlegen (keine Allokation pro Frame)
    val binMin = remember(windowBins) { FloatArray(windowBins) }
    val binMax = remember(windowBins) { FloatArray(windowBins) }
    val cols = remember { arrayOf(FloatArray(0), FloatArray(0)) }

    Canvas(modifier = modifier.fillMaxSize()) {
        @Suppress("UNUSED_VARIABLE") val t = tick.value
        val leftPad = 34f * density
        val bottomPad = 16f * density
        val w = size.width - leftPad
        val h = size.height - bottomPad
        if (w < 2f || h <= 0f) return@Canvas
        val nCols = w.toInt()
        if (cols[0].size < nCols) {
            cols[0] = FloatArray(nCols)
            cols[1] = FloatArray(nCols)
        }
        val colMin = cols[0]
        val colMax = cols[1]

        val n = waveformState.copyLast(windowBins, binMin, binMax)
        columnMinMax(binMin, binMax, n, windowBins, nCols, colMin, colMax)
        val range = niceAmplitudeRange(peakAmplitude(colMin, colMax, nCols))
        val mid = h / 2f
        fun yOf(a: Float) = mid - (a / range).coerceIn(-1f, 1f) * mid

        // Achsen: Nulllinie und +/- Skalenwert
        val grid = textColor.copy(alpha = 0.18f)
        for (a in floatArrayOf(-range, 0f, range)) {
            val y = yOf(a)
            drawLine(grid, Offset(leftPad, y), Offset(size.width, y), 1f)
            val lay = measurer.measure(amplitudeLabel(a), labelStyle)
            val ly = (y - lay.size.height / 2f).coerceIn(0f, maxOf(0f, h - lay.size.height))
            drawText(lay, topLeft = Offset(leftPad - lay.size.width - 4f * density, ly))
        }
        // Zeitachse unten: links "-Ns", rechts "Jetzt"
        val tl = measurer.measure("-${realWindowSec.toInt()}s", labelStyle)
        drawText(tl, topLeft = Offset(leftPad, h + 2f * density))
        val tn = measurer.measure(nowText, labelStyle)
        drawText(tn, topLeft = Offset(size.width - tn.size.width, h + 2f * density))

        // Wellenform: ein Pfad mit vertikalen Strichen (min..max) je Pixelspalte
        val path = Path()
        for (c in 0 until nCols) {
            if (!columnHasData(colMin[c], colMax[c])) continue
            val x = leftPad + c + 0.5f
            var y1 = yOf(colMax[c])
            var y2 = yOf(colMin[c])
            if (y2 - y1 < 1f) {  // Stille bleibt als duenne Linie sichtbar
                y1 -= 0.5f
                y2 += 0.5f
            }
            path.moveTo(x, y1)
            path.lineTo(x, y2)
        }
        drawPath(path, lineColor, style = Stroke(width = 1f))
    }
}
