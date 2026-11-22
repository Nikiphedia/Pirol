package ch.etasystems.pirol.ui.components

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import ch.etasystems.pirol.audio.dsp.MelFilterbank
import ch.etasystems.pirol.audio.dsp.SpectrogramConfig
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

/**
 * T193: Glaettung und Peak-Hold fuer das Live-Spektrum (rein, ohne Android).
 *
 * [avg] = exponentiell gleitendes Mittel in dB, [peak] = Spitzenwert, der pro Sekunde um
 * [peakDecayDbPerSec] faellt.
 */
internal class SpectrumSmoother(
    private val alpha: Float = 0.45f,
    private val peakDecayDbPerSec: Float = 25f
) {
    var avg = FloatArray(0)
        private set
    var peak = FloatArray(0)
        private set

    fun reset() {
        avg = FloatArray(0)
        peak = FloatArray(0)
    }

    /** Nimmt [frame] (dB je Bin) auf; [dtMs] = Zeit seit dem letzten Aufruf. */
    fun update(frame: FloatArray, dtMs: Long) {
        if (avg.size != frame.size) {
            avg = frame.copyOf()
            peak = frame.copyOf()
            return
        }
        val fall = peakDecayDbPerSec * dtMs.coerceIn(0L, 1000L) / 1000f
        for (i in frame.indices) {
            val v = if (frame[i].isFinite()) frame[i] else avg[i]
            avg[i] += alpha * (v - avg[i])
            peak[i] = maxOf(v, peak[i] - fall)
        }
    }
}

/** T193: Frequenz-Grenzen (Hz) des sichtbaren Bin-Bereichs (Bins linear in Mel). */
internal fun visibleBinEdgesHz(numBins: Int, binRange: IntRange, fMin: Float, fMax: Float): Pair<Float, Float> {
    val melMin = MelFilterbank.hzToMel(fMin)
    val span = MelFilterbank.hzToMel(fMax) - melMin
    fun edge(b: Int) = MelFilterbank.melToHz(melMin + span * b / numBins)
    return edge(binRange.first) to edge(binRange.last + 1)
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

/**
 * T193: Live-Spektrum (Amplitude in dB ueber Frequenz) des aktuellen Frames.
 *
 * Nutzt die Mel-Frames des Sonogramms (keine zweite FFT); die x-Achse ist wie das Sonogramm
 * Mel-skaliert, beschriftet in kHz. Zwei Linien: gleitendes Mittel (gefuellt) und Peak-Hold.
 * Gezeichnet wird nur in der Draw-Phase.
 */
@Composable
fun SpectrumView(
    spectrogramState: SpectrogramState,
    config: SpectrogramConfig,
    settings: SpectrogramDisplaySettings,
    modifier: Modifier = Modifier
) {
    val lightBg = settings.palette.isLight
    val textColor = if (lightBg) Color.Black else Color.White
    val lineColor = remember(settings.palette) { colorForValue(0.85f, settings.palette) }
    val cfgMax = if (config.fMax <= 0f) 24000f else config.fMax
    val binRange = remember(config, settings.freqMinHz, settings.freqMaxHz) {
        visibleBinRange(config.numMelBins, config.fMin, cfgMax, settings.freqMinHz, settings.freqMaxHz)
    }
    val edges = remember(config, binRange) { visibleBinEdgesHz(config.numMelBins, binRange, config.fMin, cfgMax) }
    val freqTicks = remember(edges) { computeFrequencyTicks(edges.first, edges.second) }

    val measurer = rememberTextMeasurer()
    val labelStyle = remember(textColor) { TextStyle(fontSize = 9.sp, color = textColor.copy(alpha = 0.85f)) }
    val freqLabels = remember(freqTicks, labelStyle) { freqTicks.map { measurer.measure(it.label, labelStyle) } }

    val smoother = remember(spectrogramState) { SpectrumSmoother() }
    val lastMs = remember(spectrogramState) { longArrayOf(0L) }
    val tick = rememberDrawTick(spectrogramState) { spectrogramState.frameVersion }

    Canvas(modifier = modifier.fillMaxSize()) {
        @Suppress("UNUSED_VARIABLE") val t = tick.value
        val frame = spectrogramState.getVisibleFrames(1).firstOrNull() ?: return@Canvas
        val now = SystemClock.elapsedRealtime()
        smoother.update(frame, if (lastMs[0] == 0L) 0L else now - lastMs[0])
        lastMs[0] = now

        // dB-Bereich wie das Sonogramm (Auto: Rolling-Perzentile, sonst manuell)
        val (rawMin, rawMax) = if (settings.autoContrast)
            spectrogramState.dynamicRangeMapper?.currentRange() ?: (config.minDb to 0f)
        else settings.minDb to settings.maxDb
        val (eMin, eMax) = effectiveDbRange(rawMin, rawMax, settings.ceilingDb)
        val (minDb, maxDb) = finiteDbRange(eMin, eMax, config.minDb, 0f)

        val leftPad = 34f * density
        val bottomPad = 16f * density
        val w = size.width - leftPad
        val h = size.height - bottomPad
        if (w <= 0f || h <= 0f) return@Canvas

        fun yOf(db: Float) = h * (1f - ((db - minDb) / (maxDb - minDb)).coerceIn(0f, 1f))

        // Gitter + dB-Beschriftung
        val grid = textColor.copy(alpha = 0.15f)
        for (db in dbTicks(minDb, maxDb)) {
            val y = yOf(db.toFloat())
            drawLine(grid, Offset(leftPad, y), Offset(size.width, y), 1f)
            val lay = measurer.measure("$db", labelStyle)
            val ly = (y - lay.size.height / 2f).coerceIn(0f, maxOf(0f, h - lay.size.height))
            drawText(lay, topLeft = Offset(leftPad - lay.size.width - 4f * density, ly))
        }
        // Frequenz-Gitter + kHz-Beschriftung
        freqTicks.forEachIndexed { i, tk ->
            val x = leftPad + w * frequencyFraction(tk.hz, edges.first, edges.second)
            drawLine(grid, Offset(x, 0f), Offset(x, h), 1f)
            val lay = freqLabels[i]
            val lx = (x - lay.size.width / 2f).coerceIn(leftPad, maxOf(leftPad, size.width - lay.size.width))
            drawText(lay, topLeft = Offset(lx, h + 2f * density))
        }

        val avg = smoother.avg
        val peak = smoother.peak
        val n = binRange.last - binRange.first + 1
        if (avg.size < binRange.last + 1 || n < 2) return@Canvas
        val line = Path()
        val fill = Path()
        val peakPath = Path()
        for (k in 0 until n) {
            val x = leftPad + w * k / (n - 1)
            val ya = yOf(avg[binRange.first + k])
            val yp = yOf(peak[binRange.first + k])
            if (k == 0) {
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
        fill.lineTo(leftPad + w, h)
        fill.close()
        drawPath(fill, lineColor.copy(alpha = 0.25f))
        drawPath(peakPath, lineColor.copy(alpha = 0.55f), style = Stroke(width = 1f * density))
        drawPath(line, lineColor, style = Stroke(width = 1.5f * density))
    }
}
