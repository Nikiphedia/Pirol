package ch.etasystems.pirol.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.etasystems.pirol.R
import java.util.Locale

/** Platz links fuer die Amplituden-Beschriftung. */
private const val WAVE_LEFT_PAD_DP = 34f

/** Farbe fuer Uebersteuerung (auf hellem und dunklem Grund lesbar). */
private val CLIP_COLOR = Color(0xFFE53935)

/**
 * T193: Live-Oszillogramm (Wellenform, Amplitude ueber Zeit) im Zeitfenster des Sonogramms.
 *
 * Pro Pixelspalte ein Min/Max-Strich aus dem [WaveformState]. Gezeichnet nur in der Draw-Phase.
 * T200: Amplitudenachse linear (Auto, feste Stufe oder gemeinsamer Maximalpegel) oder dBFS
 * (gemeinsamer dB-Bereich); Farben aus dem gemeinsamen Farbmuster. Oben links Peak/RMS (nach
 * Bandpass) und ggf. Uebersteuerung (Rohsignal), uebersteuerte Spalten rot markiert. Tippen setzt
 * den Cursor (Zeit + Amplitude), Tippen nahe am Cursor entfernt ihn; Knopf oben rechts friert ein.
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
    waveformSettings: WaveformSettings,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier
) {
    val lightBg = settings.palette.isLight
    val textColor = if (lightBg) Color.Black else Color.White
    val lineColor = remember(settings.palette) { colorForValue(0.85f, settings.palette) }
    val cursorColor = if (lightBg) Color(0xFF1565C0) else Color(0xFFFFEB3B)
    val measurer = rememberTextMeasurer()
    val labelStyle = remember(textColor) { TextStyle(fontSize = 9.sp, color = textColor.copy(alpha = 0.85f)) }
    val readoutStyle = remember(textColor) {
        TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = textColor)
    }
    val nowText = stringResource(R.string.ui_jetzt)
    val peakLabel = stringResource(R.string.waveform_peak_label)
    val clipLabel = stringResource(R.string.waveform_clip_label)
    val cursorLabel = stringResource(R.string.spectrum_cursor_label)
    val windowBins = (windowSec * sampleRate / waveformState.binSize).toInt()
        .coerceIn(16, waveformState.maxBins)
    val realWindowSec = windowBins.toFloat() * waveformState.binSize / sampleRate
    val minDb = settings.minDb
    val maxDb = maxOf(settings.maxDb, minDb + MIN_DISPLAY_SPAN_DB)
    val dbAxis = waveformSettings.axis == WaveformAxis.DBFS

    val tick = rememberDrawTick(waveformState) { waveformState.version }
    // Arbeitspuffer einmal anlegen (keine Allokation pro Frame)
    val binMin = remember(windowBins) { FloatArray(windowBins) }
    val binMax = remember(windowBins) { FloatArray(windowBins) }
    val binSq = remember(windowBins) { FloatArray(windowBins) }
    val binRaw = remember(windowBins) { FloatArray(windowBins) }
    val cols = remember { arrayOf(FloatArray(0), FloatArray(0), FloatArray(0)) }
    // Cursor als Anteil der Zeichenbreite 0..1 (NaN = kein Cursor)
    val cursorFrac = remember { mutableFloatStateOf(Float.NaN) }
    val currentLongPress by rememberUpdatedState(onLongPress)

    Box(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(waveformState) {
                    detectTapGestures(
                        onLongPress = { currentLongPress() },
                        onTap = { pos ->
                            val left = WAVE_LEFT_PAD_DP.dp.toPx()
                            val w = size.width - left
                            if (w <= 0f || pos.x < left) return@detectTapGestures
                            val old = cursorFrac.floatValue
                            val near = old.isFinite() && kotlin.math.abs(left + old * w - pos.x) < 24.dp.toPx()
                            cursorFrac.floatValue = if (near) Float.NaN else ((pos.x - left) / w).coerceIn(0f, 1f)
                        }
                    )
                }
        ) {
            @Suppress("UNUSED_VARIABLE") val t = tick.value
            val leftPad = WAVE_LEFT_PAD_DP * density
            val bottomPad = 16f * density
            val w = size.width - leftPad
            val h = size.height - bottomPad
            if (w < 2f || h <= 0f) return@Canvas
            val nCols = w.toInt()
            if (cols[0].size < nCols) {
                cols[0] = FloatArray(nCols)
                cols[1] = FloatArray(nCols)
                cols[2] = FloatArray(nCols)
            }
            val colMin = cols[0]
            val colMax = cols[1]
            val colRaw = cols[2]

            val n = waveformState.copyLast(windowBins, binMin, binMax, binSq, binRaw)
            columnMinMax(binMin, binMax, n, windowBins, nCols, colMin, colMax)
            columnMax(binRaw, n, windowBins, nCols, colRaw)
            val range = waveformFullScale(waveformSettings.amplitudeMode, peakAmplitude(colMin, colMax, nCols), maxDb)
            val mid = h / 2f
            // Auslenkung -1..1 je nach Achse
            fun defl(a: Float) = if (dbAxis) dbfsDeflection(a, minDb, maxDb) else (a / range).coerceIn(-1f, 1f)
            fun yOf(a: Float) = mid - defl(a) * mid

            // Achsen: Nulllinie und Rand (+/-), Beschriftung linear bzw. in dBFS
            val grid = textColor.copy(alpha = 0.18f)
            for (s in intArrayOf(-1, 0, 1)) {
                val y = mid - s * mid
                drawLine(grid, Offset(leftPad, y), Offset(size.width, y), 1f)
                val text = when {
                    dbAxis && s == 0 -> "${minDb.toInt()}"
                    dbAxis -> "${maxDb.toInt()}"
                    else -> amplitudeLabel(s * range)
                }
                val lay = measurer.measure(text, labelStyle)
                val ly = (y - lay.size.height / 2f).coerceIn(0f, maxOf(0f, h - lay.size.height))
                drawText(lay, topLeft = Offset(leftPad - lay.size.width - 4f * density, ly))
            }
            // Zeitachse unten: links "-Ns", rechts "Jetzt"
            val tl = measurer.measure("-${realWindowSec.toInt()}s", labelStyle)
            drawText(tl, topLeft = Offset(leftPad, h + 2f * density))
            val tn = measurer.measure(nowText, labelStyle)
            drawText(tn, topLeft = Offset(size.width - tn.size.width, h + 2f * density))

            // Wellenform: ein Pfad mit vertikalen Strichen (min..max) je Pixelspalte,
            // uebersteuerte Spalten (Rohsignal) zusaetzlich rot
            val path = Path()
            val clipPath = Path()
            for (c in 0 until nCols) {
                if (!columnHasData(colMin[c], colMax[c])) continue
                val x = leftPad + c + 0.5f
                var y1 = yOf(colMax[c])
                var y2 = yOf(colMin[c])
                if (y2 - y1 < 1f) {  // Stille bleibt als duenne Linie sichtbar
                    y1 -= 0.5f
                    y2 += 0.5f
                }
                val target = if (ch.etasystems.pirol.audio.dsp.isClipping(colRaw[c])) clipPath else path
                target.moveTo(x, y1)
                target.lineTo(x, y2)
                if (target === clipPath) {  // Marke am oberen Rand, auch wenn gefiltert leise
                    clipPath.moveTo(x, 0f)
                    clipPath.lineTo(x, 3f * density)
                }
            }
            drawPath(path, lineColor, style = Stroke(width = 1f))
            drawPath(clipPath, CLIP_COLOR, style = Stroke(width = 1f))

            // Ablesen: Peak/RMS des Fensters (gefiltert), Uebersteuerung, Cursor
            var ty = 4f * density
            if (n > 0) {
                val lv = waveformLevels(binMin, binMax, binSq, binRaw, n, waveformState.binSize)
                val lay = measurer.measure(levelsReadout(peakLabel, lv), readoutStyle)
                drawText(lay, topLeft = Offset(leftPad + 6f * density, ty))
                ty += lay.size.height
                if (lv.clipping) {
                    val cl = measurer.measure(clipLabel, readoutStyle.copy(color = CLIP_COLOR))
                    drawText(cl, topLeft = Offset(leftPad + 6f * density, ty))
                    ty += cl.size.height
                }
            }
            val cf = cursorFrac.floatValue
            if (cf.isFinite()) {
                val c = (cf * nCols).toInt().coerceIn(0, nCols - 1)
                val cx = leftPad + c + 0.5f
                drawLine(cursorColor, Offset(cx, 0f), Offset(cx, h), 1.5f * density)
                val tSec = -(1f - cf) * realWindowSec
                val text = if (columnHasData(colMin[c], colMax[c])) {
                    val a = maxOf(kotlin.math.abs(colMin[c]), kotlin.math.abs(colMax[c]))
                    "%s %.2f s  %s".format(Locale.US, cursorLabel, tSec, amplitudeReadout(a))
                } else "%s %.2f s".format(Locale.US, cursorLabel, tSec)
                val lay = measurer.measure(text, readoutStyle.copy(color = cursorColor))
                drawText(lay, topLeft = Offset(leftPad + 6f * density, ty))
            }
        }
        val frozen = waveformState.frozen
        IconButton(
            onClick = { waveformState.frozen = !frozen },
            modifier = Modifier.align(Alignment.TopEnd).padding(2.dp).size(48.dp)
        ) {
            Icon(
                imageVector = if (frozen) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                contentDescription = stringResource(if (frozen) R.string.spectrum_unfreeze else R.string.spectrum_freeze),
                tint = if (frozen) cursorColor else textColor.copy(alpha = 0.8f)
            )
        }
    }
}
