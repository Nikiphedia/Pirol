package ch.etasystems.pirol.ui.components

import androidx.annotation.StringRes
import ch.etasystems.pirol.R
import java.util.Locale

/**
 * T198: Einstellungen des Live-Spektrums (eigenes Panel, persistent ueber `pirol_spectrum_*`).
 * T199: Frequenz- und dB-Bereich sowie Palette kommen aus [SpectrogramDisplaySettings] (gemeinsam).
 *
 * @param fftSize FFT-Groesse (siehe SpectrumAnalyzer.FFT_SIZES)
 * @param averaging exponentielle Mittelung
 * @param peakHold Peak-Hold-Linie anzeigen
 * @param traceSec T201: Dauer der rollenden Peak-Spuren (verblassende Max-Kurven), 0 = aus
 */
data class SpectrumSettings(
    val fftSize: Int = 2048,
    val averaging: SpectrumAveraging = SpectrumAveraging.SHORT,
    val peakHold: Boolean = false,
    val traceSec: Float = 0f
)

/** T201: Auswahl der Spuren-Dauer in Sekunden (0 = aus). */
val SPECTRUM_TRACE_OPTIONS = floatArrayOf(0f, 1f, 2f, 5f, 10f, 30f)

/** T198: Mittelung aus / kurz / lang (Zeitkonstante tau in Sekunden). */
enum class SpectrumAveraging(val tauSec: Float, @StringRes val labelRes: Int) {
    OFF(0f, R.string.spectrum_avg_off),
    SHORT(0.3f, R.string.spectrum_avg_short),
    LONG(1.5f, R.string.spectrum_avg_long)
}

/** T198: Mindestbreite des dB-Bereichs (T199: Frequenz-Mindestbreite siehe SHARED_MIN_SPAN_HZ). */
const val SPECTRUM_MIN_SPAN_DB = 10f

/** T198: Lineare Abbildung Frequenz -> x (Pixel ab [left], Breite [width]); nicht begrenzt. */
internal fun spectrumHzToX(hz: Float, fLo: Float, fHi: Float, left: Float, width: Float): Float =
    left + width * (hz - fLo) / (fHi - fLo)

/** T198: Umkehrung von [spectrumHzToX], begrenzt auf [fLo, fHi]. */
internal fun spectrumXToHz(x: Float, fLo: Float, fHi: Float, left: Float, width: Float): Float =
    (fLo + (x - left) / width * (fHi - fLo)).coerceIn(fLo, fHi)

/** Moegliche Gitterschritte in Hz (1-kHz-Raster, bei engem Bereich feiner). */
private val FREQ_STEPS_HZ = floatArrayOf(100f, 200f, 500f, 1000f, 2000f, 5000f)

/** T198: Schritt so, dass hoechstens [maxTicks] Linien im Bereich liegen (mindestens 1 kHz ab 6 kHz Spanne). */
internal fun spectrumFreqStepHz(fLo: Float, fHi: Float, maxTicks: Int = 12): Float {
    val span = fHi - fLo
    for (s in FREQ_STEPS_HZ) if (span / s <= maxTicks) return s
    return FREQ_STEPS_HZ.last()
}

/** T198: Gitter-Frequenzen (Hz) im Bereich [fLo, fHi] als Vielfache des Schritts. */
internal fun spectrumFreqTicks(fLo: Float, fHi: Float, maxTicks: Int = 12): List<Float> {
    val step = spectrumFreqStepHz(fLo, fHi, maxTicks)
    val out = ArrayList<Float>()
    var k = Math.ceil(fLo / step.toDouble()).toLong()
    while (k * step <= fHi + 0.01f) {
        out.add(k * step)
        k++
    }
    return out
}

/** T198: Achsen-Beschriftung in kHz ("2", "2.5", "0.2"). */
internal fun khzLabel(hz: Float): String {
    val khz = hz / 1000f
    return if (Math.abs(khz - Math.round(khz)) < 1e-3f) Math.round(khz).toString()
    else "%.1f".format(Locale.US, khz)
}

/** T198: Ablese-Text "3.45 kHz  -23.1 dBFS". */
internal fun spectrumReadout(hz: Float, db: Float): String =
    "%.2f kHz  %.1f dBFS".format(Locale.US, hz / 1000f, db)

/** T198: Index des Maximums von [values] im Bereich [from, to] (inklusive); -1 wenn leer. */
internal fun argMaxIn(values: FloatArray, from: Int, to: Int): Int {
    var best = -1
    var bestV = Float.NEGATIVE_INFINITY
    for (i in maxOf(0, from)..minOf(values.size - 1, to)) {
        if (values[i] > bestV) { bestV = values[i]; best = i }
    }
    return best
}
