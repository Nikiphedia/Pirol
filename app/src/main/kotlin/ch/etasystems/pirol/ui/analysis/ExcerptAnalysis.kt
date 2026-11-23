package ch.etasystems.pirol.ui.analysis

import ch.etasystems.pirol.audio.dsp.SpectrumAnalyzer
import kotlin.math.log10
import kotlin.math.pow

/**
 * T205: Reine Logik fuer Spektrum und Oszillogramm in der Analyse (Ausschnitt statt Live-Strom).
 */

/** Breite des Fensters um die Abspielposition fuer das Spektrum (Sekunden). */
internal const val ANALYSIS_SPECTRUM_WINDOW_SEC = 0.5f

/** Maximale Bin-Anzahl des statischen Oszillogramms (entspricht etwa der Pixelbreite). */
internal const val ANALYSIS_WAVEFORM_MAX_BINS = 4096

/**
 * Sample-Bereich [von, bis) im Ausschnitt, ueber den das Spektrum gerechnet wird.
 * Ohne Abspielposition (oder ausserhalb des Ausschnitts) der ganze Ausschnitt, sonst ein Fenster
 * von [windowSec] um die Position. [excerptStartSec] und [posSec] liegen auf der Session-Achse.
 */
internal fun spectrumSliceRange(
    totalSamples: Int, sampleRate: Int, excerptStartSec: Float, posSec: Float?,
    windowSec: Float = ANALYSIS_SPECTRUM_WINDOW_SEC
): Pair<Int, Int> {
    if (totalSamples <= 0 || sampleRate <= 0) return 0 to 0
    if (posSec == null) return 0 to totalSamples
    val rel = posSec - excerptStartSec
    if (rel < 0f || rel * sampleRate > totalSamples) return 0 to totalSamples
    val half = (windowSec * sampleRate / 2f).toInt().coerceAtLeast(1)
    val center = (rel * sampleRate).toInt()
    val from = (center - half).coerceIn(0, totalSamples - 1)
    val to = (center + half).coerceIn(from + 1, totalSamples)
    return from to to
}

/** Mittleres Spektrum (Leistungsmittel, dBFS) und Maximum je Bin (dBFS) ueber [mean]/[max]. */
internal class ExcerptSpectrum(val mean: FloatArray, val max: FloatArray)

/**
 * Spektrum von Samples [from, bis) in Frames der Laenge [fftSize] mit 50 % Ueberlappung.
 * Ist der Bereich kuerzer als ein Frame, wird ein nullaufgefuellter Frame gerechnet.
 * Gleiche dBFS-Skala wie das Live-Spektrum ([SpectrumAnalyzer.computeDbfs]).
 */
internal fun computeExcerptSpectrum(samples: ShortArray, from: Int, to: Int, fftSize: Int): ExcerptSpectrum? {
    val end = to.coerceAtMost(samples.size)
    if (from < 0 || end <= from) return null
    val analyzer = SpectrumAnalyzer(fftSize)
    val bins = analyzer.numBins
    val seg = FloatArray(end - from) { samples[from + it] / 32768f }
    val hop = fftSize / 2
    val power = DoubleArray(bins)
    val max = FloatArray(bins) { Float.NEGATIVE_INFINITY }
    val frame = FloatArray(bins)
    var frames = 0
    var off = 0
    do {
        analyzer.computeDbfs(seg, off, frame)
        for (k in 0 until bins) {
            power[k] += 10.0.pow(frame[k] / 10.0)
            if (frame[k] > max[k]) max[k] = frame[k]
        }
        frames++
        off += hop
    } while (off + fftSize <= seg.size)
    val mean = FloatArray(bins) { (10.0 * log10(power[it] / frames)).toFloat().coerceAtLeast(SpectrumAnalyzer.FLOOR_DBFS) }
    return ExcerptSpectrum(mean, max)
}

/** Samples pro Bin, damit [sampleCount] Samples in hoechstens [maxBins] Bins passen. */
internal fun staticWaveformBinSize(sampleCount: Int, maxBins: Int = ANALYSIS_WAVEFORM_MAX_BINS): Int =
    if (sampleCount <= maxBins) 1 else (sampleCount + maxBins - 1) / maxBins

/** Zeitachsen-Beschriftung: ab 60 s als m:ss, sonst in Sekunden; [decimals] Nachkommastellen. */
internal fun analysisTimeLabel(sec: Float, decimals: Int = 0): String {
    val scale = 10.0.pow(decimals).toLong()
    val units = Math.round(sec.coerceAtLeast(0f).toDouble() * scale)  // ganze Einheiten (1/scale s)
    val whole = units / scale
    val frac = units % scale
    val fracText = if (decimals > 0) "." + frac.toString().padStart(decimals, '0') else ""
    return if (whole >= 60) "%d:%02d%s".format(java.util.Locale.US, whole / 60, whole % 60, fracText)
    else "$whole$fracText s"
}
