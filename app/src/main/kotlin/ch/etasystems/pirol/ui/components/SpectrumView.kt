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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import ch.etasystems.pirol.R
import ch.etasystems.pirol.audio.dsp.SpectrumAnalyzer
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * T193: Zeichen-Takt fuer die Live-Ansichten (wie beim Sonogramm, T160): hoechstens ~25 fps,
 * nur bei neuer [version] und nur solange die Ansicht sichtbar (STARTED) ist. Der zurueckgegebene
 * State wird nur im Draw-Lambda gelesen, es gibt also keine Recomposition.
 */
@Composable
internal fun rememberDrawTick(key: Any, version: () -> Int): State<Int> {
    val tick = remember(key) { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(key, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var seen = -1
            while (isActive) {
                val v = version()
                if (v != seen) {
                    seen = v
                    tick.intValue++
                }
                delay(SPECTROGRAM_RENDER_INTERVAL_MS)
            }
        }
    }
    return tick
}

/** T193: dB-Marken (Gitterlinien) im Bereich [minDb, maxDb] mit Schritt 10 bzw. 20 dB. */
internal fun dbTicks(minDb: Float, maxDb: Float): List<Int> {
    val step = if (maxDb - minDb > 60f) 20 else 10
    val out = ArrayList<Int>()
    var v = Math.ceil(minDb / step.toDouble()).toInt() * step
    while (v <= maxDb) {
        out.add(v)
        v += step
    }
    return out
}

/** Platz links fuer die dB-Beschriftung. */
private const val LEFT_PAD_DP = 34f

/**
 * T198: Live-Spektrum aus echter FFT ([SpectrumState]), lineare Frequenzachse in kHz.
 *
 * Gefuellte Linie = gemitteltes Spektrum, duenne Linie = Peak-Hold (falls an). Oben links
 * Maximum (Frequenz + dBFS) und ggf. Cursor-Wert. Tippen setzt/verschiebt den Cursor (Tippen
 * nahe am Cursor entfernt ihn), Langdruck oeffnet das Panel, Knopf oben rechts friert ein.
 * Die FFT laeuft im Collector; hier wird nur gezeichnet (~25 fps).
 */
@Composable
fun SpectrumView(
    spectrumState: SpectrumState,
    settings: SpectrumSettings,
    palette: SpectrogramPalette,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier
) {
    val lightBg = palette.isLight
    val textColor = if (lightBg) Color.Black else Color.White
    val lineColor = remember(palette) { colorForValue(0.85f, palette) }
    val cursorColor = if (lightBg) Color(0xFFD32F2F) else Color(0xFFFFEB3B)
    val measurer = rememberTextMeasurer()
    val labelStyle = remember(textColor) { TextStyle(fontSize = 9.sp, color = textColor.copy(alpha = 0.85f)) }
    val readoutStyle = remember(textColor) {
        TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = textColor)
    }
    val peakLabel = stringResource(R.string.spectrum_peak_label)
    val cursorLabel = stringResource(R.string.spectrum_cursor_label)

    // Cursor-Frequenz in Hz (NaN = kein Cursor)
    val cursorHz = remember { mutableFloatStateOf(Float.NaN) }
    val bufs = remember(spectrumState) { arrayOfNulls<FloatArray>(2) }
    val tick = rememberDrawTick(spectrumState) { spectrumState.version }

    val sr = spectrumState.sampleRate
    val fLo = settings.freqMinHz.coerceIn(0f, sr / 2f - SPECTRUM_MIN_SPAN_HZ)
    val fHi = settings.freqMaxHz.coerceIn(fLo + SPECTRUM_MIN_SPAN_HZ, sr / 2f)
    val minDb = settings.minDb
    val maxDb = maxOf(settings.maxDb, minDb + SPECTRUM_MIN_SPAN_DB)
    val freqTicks = remember(fLo, fHi) { spectrumFreqTicks(fLo, fHi) }
    val currentLongPress by rememberUpdatedState(onLongPress)
    val range by rememberUpdatedState(fLo to fHi)

    Box(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(spectrumState) {
                    detectTapGestures(
                        onLongPress = { currentLongPress() },
                        onTap = { pos ->
                            val left = LEFT_PAD_DP.dp.toPx()
                            val w = size.width - left
                            if (w <= 0f || pos.x < left) return@detectTapGestures
                            val (lo, hi) = range
                            val old = cursorHz.floatValue
                            val near = old.isFinite() &&
                                kotlin.math.abs(spectrumHzToX(old, lo, hi, left, w) - pos.x) < 24.dp.toPx()
                            cursorHz.floatValue = if (near) Float.NaN else spectrumXToHz(pos.x, lo, hi, left, w)
                        }
                    )
                }
        ) {
            @Suppress("UNUSED_VARIABLE") val t = tick.value
            val leftPad = LEFT_PAD_DP * density
            val bottomPad = 16f * density
            val w = size.width - leftPad
            val h = size.height - bottomPad
            if (w <= 0f || h <= 0f) return@Canvas
            fun yOf(db: Float) = h * (1f - ((db - minDb) / (maxDb - minDb)).coerceIn(0f, 1f))
            fun xOf(hz: Float) = spectrumHzToX(hz, fLo, fHi, leftPad, w)

            // Gitter + Beschriftung
            val grid = textColor.copy(alpha = 0.15f)
            for (db in dbTicks(minDb, maxDb)) {
                val y = yOf(db.toFloat())
                drawLine(grid, Offset(leftPad, y), Offset(size.width, y), 1f)
                val lay = measurer.measure("$db", labelStyle)
                val ly = (y - lay.size.height / 2f).coerceIn(0f, maxOf(0f, h - lay.size.height))
                drawText(lay, topLeft = Offset(leftPad - lay.size.width - 4f * density, ly))
            }
            for (hz in freqTicks) {
                val x = xOf(hz)
                drawLine(grid, Offset(x, 0f), Offset(x, h), 1f)
                val lay = measurer.measure(khzLabel(hz), labelStyle)
                val lx = (x - lay.size.width / 2f).coerceIn(leftPad, maxOf(leftPad, size.width - lay.size.width))
                drawText(lay, topLeft = Offset(lx, h + 2f * density))
            }

            val copied = spectrumState.copyInto(bufs[0], bufs[1]) ?: return@Canvas
            bufs[0] = copied.first
            bufs[1] = copied.second
            val avg = copied.first
            val peak = copied.second
            val n = (avg.size - 1) * 2  // FFT-Groesse dieser Daten
            val b0 = SpectrumAnalyzer.hzToBin(fLo, n, sr)
            val b1 = SpectrumAnalyzer.hzToBin(fHi, n, sr)
            if (b1 - b0 < 1) return@Canvas

            val line = Path()
            val fill = Path()
            val peakPath = Path()
            for (b in b0..b1) {
                val x = xOf(SpectrumAnalyzer.binToHz(b, n, sr)).coerceIn(leftPad, size.width)
                val ya = yOf(avg[b])
                val yp = yOf(peak[b])
                if (b == b0) {
                    line.moveTo(x, ya)
                    fill.moveTo(x, h)
                    fill.lineTo(x, ya)
                    peakPath.moveTo(x, yp)
                } else {
                    line.lineTo(x, ya)
                    fill.lineTo(x, ya)
                    peakPath.lineTo(x, yp)
                }
            }
            fill.lineTo(xOf(SpectrumAnalyzer.binToHz(b1, n, sr)).coerceIn(leftPad, size.width), h)
            fill.close()
            drawPath(fill, lineColor.copy(alpha = 0.25f))
            if (settings.peakHold) {
                drawPath(peakPath, lineColor.copy(alpha = 0.6f), style = Stroke(width = 1f * density))
            }
            drawPath(line, lineColor, style = Stroke(width = 1.5f * density))

            // Ablesen: Maximum im sichtbaren Bereich, darunter der Cursor
            var ty = 4f * density
            val pb = argMaxIn(avg, b0, b1)
            if (pb >= 0) {
                val lay = measurer.measure(
                    "$peakLabel ${spectrumReadout(SpectrumAnalyzer.binToHz(pb, n, sr), avg[pb])}", readoutStyle
                )
                drawText(lay, topLeft = Offset(leftPad + 6f * density, ty))
                ty += lay.size.height
            }
            val c = cursorHz.floatValue
            if (c.isFinite() && c in fLo..fHi) {
                val cb = SpectrumAnalyzer.hzToBin(c, n, sr)
                val cx = xOf(c)
                drawLine(cursorColor, Offset(cx, 0f), Offset(cx, h), 1.5f * density)
                val lay = measurer.measure(
                    "$cursorLabel ${spectrumReadout(SpectrumAnalyzer.binToHz(cb, n, sr), avg[cb])}",
                    readoutStyle.copy(color = cursorColor)
                )
                drawText(lay, topLeft = Offset(leftPad + 6f * density, ty))
            }
        }
        val frozen = spectrumState.frozen
        IconButton(
            onClick = { spectrumState.frozen = !frozen },
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
