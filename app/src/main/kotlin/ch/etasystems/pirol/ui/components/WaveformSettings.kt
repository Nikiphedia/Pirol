package ch.etasystems.pirol.ui.components

import androidx.annotation.StringRes
import ch.etasystems.pirol.R
import ch.etasystems.pirol.audio.dsp.amplitudeToDbfs
import ch.etasystems.pirol.audio.dsp.isClipping
import ch.etasystems.pirol.audio.dsp.rmsDbfs
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow

/** T200: Amplitudenskala (lineare Achse) des Oszillogramms. */
enum class WaveformAmplitudeMode(@StringRes val labelRes: Int) {
    AUTO(R.string.waveform_amp_auto),
    FIXED_1(R.string.waveform_amp_1),
    FIXED_01(R.string.waveform_amp_01),
    FIXED_001(R.string.waveform_amp_001),
    /** Vollausschlag = gemeinsamer Maximalpegel (maxDb), folgt jeder Aenderung in einem Panel */
    MAX_LEVEL(R.string.waveform_amp_max_level)
}

/**
 * T201: Pegelmodus der Amplitudenachse. PERCENT = linear in Prozent (Amplitudenmodus gilt),
 * FOLLOW_FFT = dBFS im gemeinsamen Bereich (wie die Spektrum-y-Achse), FOLLOW_SONO = dBFS im
 * Bereich, den das Sonogramm tatsaechlich verwendet (Auto-Kontrast bzw. Ceiling).
 */
enum class WaveformLevelMode(@StringRes val labelRes: Int) {
    PERCENT(R.string.waveform_level_percent),
    FOLLOW_FFT(R.string.waveform_level_fft),
    FOLLOW_SONO(R.string.waveform_level_sono)
}

/** T200: Oszillogramm-eigene Einstellungen (Prefs `pirol_waveform_*`). T201: Pegelmodus statt Achse. */
data class WaveformSettings(
    val amplitudeMode: WaveformAmplitudeMode = WaveformAmplitudeMode.AUTO,
    val levelMode: WaveformLevelMode = WaveformLevelMode.PERCENT
)

/**
 * T200: Vollausschlag der linearen Achse. AUTO = naechste Stufe ueber [peak], MAX_LEVEL =
 * 10^(maxDb/20) (z.B. -20 dBFS -> 0.1), sonst feste Stufe.
 */
internal fun waveformFullScale(mode: WaveformAmplitudeMode, peak: Float, maxDb: Float): Float = when (mode) {
    WaveformAmplitudeMode.AUTO -> niceAmplitudeRange(peak)
    WaveformAmplitudeMode.FIXED_1 -> 1f
    WaveformAmplitudeMode.FIXED_01 -> 0.1f
    WaveformAmplitudeMode.FIXED_001 -> 0.01f
    WaveformAmplitudeMode.MAX_LEVEL -> 10f.pow(maxDb / 20f).coerceIn(1e-7f, 1f)
}

/**
 * T201: dBFS-Bereich (unten, oben) der Oszillogramm-Achse. FOLLOW_FFT = gemeinsamer Bereich
 * (wie [SpectrumView]), FOLLOW_SONO = Bereich des Sonogramms: bei Auto-Kontrast der Bereich des
 * [ch.etasystems.pirol.audio.dsp.DynamicRangeMapper] ([autoRange]), sonst der feste; danach wie
 * dort mit Ceiling und Mindestspanne. PERCENT liefert den gemeinsamen Bereich (nicht benutzt).
 */
internal fun waveformDbRange(
    mode: WaveformLevelMode,
    settings: SpectrogramDisplaySettings,
    autoRange: Pair<Float, Float>?
): Pair<Float, Float> {
    if (mode != WaveformLevelMode.FOLLOW_SONO) {
        return settings.minDb to maxOf(settings.maxDb, settings.minDb + SPECTRUM_MIN_SPAN_DB)
    }
    val (lo, hi) = when {
        settings.autoContrast -> autoRange
            ?: (settings.minDb to ch.etasystems.pirol.audio.dsp.SONOGRAM_DEFAULT_MAX_DBFS)
        else -> settings.minDb to settings.maxDb
    }
    val (eLo, eHi) = effectiveDbRange(lo, hi, settings.ceilingDb)
    return if (eLo.isFinite() && eHi.isFinite()) eLo to eHi
    else settings.minDb to maxOf(settings.maxDb, settings.minDb + SPECTRUM_MIN_SPAN_DB)
}

/**
 * T200: Auslenkung -1..1 fuer die dBFS-Achse: Betrag in dBFS linear auf [minDb, maxDb] abgebildet
 * (minDb = Nulllinie, maxDb = Rand), Vorzeichen bleibt.
 */
internal fun dbfsDeflection(a: Float, minDb: Float, maxDb: Float): Float {
    if (a == 0f || maxDb <= minDb) return 0f
    val n = ((amplitudeToDbfs(a) - minDb) / (maxDb - minDb)).coerceIn(0f, 1f)
    return if (a < 0f) -n else n
}

/** T200: Umkehrung von [dbfsDeflection] (Betrag, linear). */
internal fun dbfsDeflectionToAmplitude(d: Float, minDb: Float, maxDb: Float): Float {
    val n = abs(d).coerceIn(0f, 1f)
    if (n == 0f) return 0f
    val a = 10f.pow((minDb + n * (maxDb - minDb)) / 20f)
    return if (d < 0f) -a else a
}

/**
 * T200: Pegel des angezeigten Fensters: Peak und RMS (nach Bandpass) in dBFS sowie der
 * ungefilterte Spitzenbetrag (linear, fuer Uebersteuerung).
 */
internal data class WaveformLevels(val peakDbfs: Float, val rmsDbfs: Float, val rawPeak: Float) {
    val clipping: Boolean get() = isClipping(rawPeak)
}

/** T200: Pegel aus [n] Bins (je [binSize] Samples) von [WaveformState.copyLast]. */
internal fun waveformLevels(
    binMin: FloatArray, binMax: FloatArray, sumSq: FloatArray, rawPeak: FloatArray, n: Int, binSize: Int
): WaveformLevels {
    var peak = 0f
    var sum = 0.0
    var raw = 0f
    for (i in 0 until n) {
        peak = maxOf(peak, abs(binMin[i]), abs(binMax[i]))
        sum += sumSq[i]
        raw = maxOf(raw, rawPeak[i])
    }
    return WaveformLevels(amplitudeToDbfs(peak), rmsDbfs(sum, n.toLong() * binSize), raw)
}

/** T200: Groesster Wert je Pixelspalte (rechtsbuendig wie [columnMinMax]); leere Spalten = -1. */
internal fun columnMax(values: FloatArray, n: Int, windowBins: Int, columns: Int, out: FloatArray) {
    val offset = windowBins - n
    for (c in 0 until columns) {
        out[c] = -1f
        val a = (c.toLong() * windowBins / columns).toInt()
        val b = maxOf(a + 1, ((c + 1).toLong() * windowBins / columns).toInt())
        for (w in a until b) {
            val i = w - offset
            if (i < 0 || i >= n) continue
            if (values[i] > out[c]) out[c] = values[i]
        }
    }
}

/** T200: Ablese-Text Amplitude, z.B. "0.0312  -30.1 dBFS". */
internal fun amplitudeReadout(a: Float): String =
    "%.4f  %.1f dBFS".format(Locale.US, abs(a), amplitudeToDbfs(a))

/** T200: Ablese-Text Pegel, z.B. "Peak -12.3  RMS -20.5 dBFS". */
internal fun levelsReadout(peakLabel: String, levels: WaveformLevels): String =
    "%s %.1f  RMS %.1f dBFS".format(Locale.US, peakLabel, levels.peakDbfs, levels.rmsDbfs)
