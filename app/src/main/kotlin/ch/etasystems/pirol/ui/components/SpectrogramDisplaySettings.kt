package ch.etasystems.pirol.ui.components

import androidx.annotation.StringRes
import ch.etasystems.pirol.R
import ch.etasystems.pirol.audio.dsp.MelFilterbank
import ch.etasystems.pirol.audio.dsp.SONOGRAM_DEFAULT_MAX_DBFS
import ch.etasystems.pirol.audio.dsp.SONOGRAM_DEFAULT_MIN_DBFS
import ch.etasystems.pirol.audio.dsp.SpectrogramConfig
import kotlin.math.ceil
import kotlin.math.floor

/**
 * T107: Gemeinsame Darstellungs-Einstellungen fuer Live-Tab, Analyse-Tab und Settings.
 *
 * T199: Frequenzbereich, dB-Bereich (dBFS) und Palette gelten gemeinsam fuer Sonogramm,
 * Spektrum und (ab T200) Oszillogramm; Regler dafuer in [SharedDisplayControls].
 *
 * Reine Datenklasse (keine Android-Abhaengigkeit, unit-testbar). Gehalten und persistiert
 * von `SpectrogramSettingsRepository`.
 *
 * @param autoContrast true = dB-Bereich aus Perzentilen (Live: Rolling, Analyse: ganze Session)
 * @param minDb Untergrenze in dBFS (Sonogramm nur bei autoContrast=false; Spektrum immer)
 * @param maxDb Obergrenze in dBFS (Sonogramm nur bei autoContrast=false; Spektrum immer)
 * @param gamma Intensitaet: < 1 = leise Anteile heller
 * @param ceilingDb Lautstaerke-Deckel in dBFS (0 = aus)
 * @param palette Farbpalette
 * @param freqMinHz untere Anzeige-Frequenz (wird auf die SpectrogramConfig begrenzt)
 * @param freqMaxHz obere Anzeige-Frequenz (wird auf die SpectrogramConfig begrenzt)
 * @param windowSec Zeitfenster des Live-Sonogramms in Sekunden
 * @param autoLowPct Auto-Kontrast: unteres Perzentil (Rausch-Untergrenze)
 * @param autoHighPct Auto-Kontrast: oberes Perzentil
 * @param autoMinSpanDb Auto-Kontrast: Mindestspanne in dB
 * @param displayMode T193: Live-Darstellung (Sonogramm, Spektrum, Oszillogramm); Analyse ignoriert das
 * @param ultraFreqMinHz T294: unterer Anzeigebereich bei Ultraschall-Sessions (eigener Wert, Voegel unberuehrt)
 * @param ultraFreqMaxHz T294: oberer Anzeigebereich bei Ultraschall-Sessions
 */
data class SpectrogramDisplaySettings(
    val autoContrast: Boolean = true,
    val minDb: Float = SONOGRAM_DEFAULT_MIN_DBFS,
    val maxDb: Float = SONOGRAM_DEFAULT_MAX_DBFS,
    val gamma: Float = 0.5f,
    val ceilingDb: Float = 0f,
    val palette: SpectrogramPalette = SpectrogramPalette.GRAYSCALE,
    val freqMinHz: Float = 0f,
    val freqMaxHz: Float = 24000f,
    val windowSec: Float = 12f,
    val autoLowPct: Float = 0.02f,
    val autoHighPct: Float = 0.98f,
    val autoMinSpanDb: Float = 20f,
    val displayMode: LiveDisplayMode = LiveDisplayMode.SONOGRAM,
    val ultraFreqMinHz: Float = SpectrogramConfig.ULTRASOUND_DISPLAY_MIN_HZ,
    val ultraFreqMaxHz: Float = SpectrogramConfig.ULTRASOUND_DISPLAY_MAX_HZ
) {
    /**
     * T294: Einstellungen fuer die Anzeige mit [config]: bei linearer (Ultraschall-)Config gilt der eigene
     * Ultraschall-Frequenzbereich, sonst unveraendert. Idempotent.
     */
    fun forConfig(config: SpectrogramConfig): SpectrogramDisplaySettings =
        if (config.linearFreq) copy(freqMinHz = ultraFreqMinHz, freqMaxHz = ultraFreqMaxHz) else this

    /** T294: Frequenzbereich setzen; bei Ultraschall-Config in die eigenen Ultraschall-Felder. */
    fun withFreqRange(config: SpectrogramConfig, lo: Float, hi: Float): SpectrogramDisplaySettings =
        if (config.linearFreq) copy(ultraFreqMinHz = lo, ultraFreqMaxHz = hi) else copy(freqMinHz = lo, freqMaxHz = hi)

    /** Setzt die Preset-Werte; Palette und Zeitfenster bleiben unveraendert. */
    fun withPreset(preset: SpectrogramPreset): SpectrogramDisplaySettings = when (preset) {
        SpectrogramPreset.STANDARD -> copy(
            autoContrast = true, minDb = SONOGRAM_DEFAULT_MIN_DBFS, maxDb = SONOGRAM_DEFAULT_MAX_DBFS,
            gamma = 0.5f, ceilingDb = 0f,
            freqMinHz = 0f, freqMaxHz = 24000f,
            autoLowPct = 0.02f, autoHighPct = 0.98f, autoMinSpanDb = 20f
        )
        // Rauschen dunkel (p30 als Untergrenze), Spitzen nicht saettigen (p99.5), grosse Mindestspanne
        SpectrogramPreset.LEISE_RUFE -> copy(
            autoContrast = true, minDb = SONOGRAM_DEFAULT_MIN_DBFS, maxDb = SONOGRAM_DEFAULT_MAX_DBFS,
            gamma = 0.35f, ceilingDb = 0f,
            freqMinHz = 0f, freqMaxHz = 24000f,
            autoLowPct = 0.30f, autoHighPct = 0.995f, autoMinSpanDb = 30f
        )
        // Fester Bereich, Hochpass-Anzeige ab 1 kHz (Wind/Verkehr ausblenden).
        // T199: in dBFS (vorher relativ -70..-10 = ca. -84..-24 dBFS)
        SpectrogramPreset.LAUTE_UMGEBUNG -> copy(
            autoContrast = false, minDb = -85f, maxDb = -25f, gamma = 0.7f, ceilingDb = 0f,
            freqMinHz = 1000f, freqMaxHz = 24000f,
            autoLowPct = 0.02f, autoHighPct = 0.98f, autoMinSpanDb = 20f
        )
    }

    /** Welches Preset passt exakt auf die aktuellen Werte (null = benutzerdefiniert). */
    fun matchingPreset(): SpectrogramPreset? =
        SpectrogramPreset.entries.firstOrNull { withPreset(it) == this }
}

/** T193: Darstellungsart der Live-Anzeige. */
enum class LiveDisplayMode(@StringRes val labelRes: Int) {
    SONOGRAM(R.string.display_mode_sonogram),
    SPECTRUM(R.string.display_mode_spectrum),
    WAVEFORM(R.string.display_mode_waveform)
}

/** Die 3 Presets des Sonogramm-Panels. */
enum class SpectrogramPreset(@StringRes val labelRes: Int) {
    STANDARD(R.string.spectro_preset_standard),
    LEISE_RUFE(R.string.spectro_preset_quiet),
    LAUTE_UMGEBUNG(R.string.spectro_preset_loud)
}

/** Kleinste erlaubte dB-Spanne zwischen Unter- und Obergrenze beim Mapping. */
const val MIN_DISPLAY_SPAN_DB = 10f

/** Mindestanzahl sichtbarer Mel-Bins bei Frequenz-Zuschnitt. */
const val MIN_VISIBLE_BINS = 8

/**
 * T107: Mapping-Bereich nach Ceiling und Mindestspanne.
 * Der Ceiling darf den Bereich nie unter [minSpanDb] zusammenschieben
 * (vorher: Ceiling nahe an minDb ergab eine winzige Spanne, Bild nur schwarz/weiss).
 */
fun effectiveDbRange(
    minDb: Float,
    maxDb: Float,
    ceilingDb: Float,
    minSpanDb: Float = MIN_DISPLAY_SPAN_DB
): Pair<Float, Float> {
    val capped = if (ceilingDb < maxDb) ceilingDb else maxDb
    val hi = if (capped - minDb < minSpanDb) minDb + minSpanDb else capped
    return minDb to hi
}

/**
 * T107: Mel-Bin-Bereich [first, last] fuer den Anzeige-Frequenzbereich.
 * Bins sind linear in Mel zwischen [configFMin] und [configFMax] verteilt.
 * Liefert immer mindestens [MIN_VISIBLE_BINS] Bins.
 */
fun visibleBinRange(
    numBins: Int,
    configFMin: Float,
    configFMax: Float,
    freqMinHz: Float,
    freqMaxHz: Float,
    linear: Boolean = false  // T294: Baender linear in Hz (Ultraschall)
): IntRange {
    if (numBins <= MIN_VISIBLE_BINS) return 0 until numBins
    fun scale(hz: Float) = if (linear) hz else MelFilterbank.hzToMel(hz)
    val melMin = scale(configFMin)
    val melMax = scale(configFMax)
    if (melMax <= melMin) return 0 until numBins
    fun frac(hz: Float) =
        (scale(hz.coerceIn(configFMin, configFMax)) - melMin) / (melMax - melMin)
    var first = floor(frac(freqMinHz) * numBins).toInt().coerceIn(0, numBins - MIN_VISIBLE_BINS)
    var last = (ceil(frac(freqMaxHz) * numBins).toInt() - 1).coerceIn(0, numBins - 1)
    if (last - first + 1 < MIN_VISIBLE_BINS) last = (first + MIN_VISIBLE_BINS - 1).coerceAtMost(numBins - 1)
    if (last - first + 1 < MIN_VISIBLE_BINS) first = last - MIN_VISIBLE_BINS + 1
    return first..last
}

/**
 * T200: Mel-Bin-Bereich fuer den Anzeige-Frequenzbereich, Grenzen aus derselben [config], mit der
 * die Mel-Frames gerechnet wurden (aufgeloest per `SpectrogramConfig.resolved`). Gemeinsam fuer
 * Live-/Analyse-Sonogramm und PNG-Export, damit Bins und Hz-Achse zusammenpassen.
 */
fun displayBinRange(
    config: ch.etasystems.pirol.audio.dsp.SpectrogramConfig,
    freqMinHz: Float,
    freqMaxHz: Float
): IntRange = visibleBinRange(
    config.numMelBins, config.fMin,
    if (config.fMax <= 0f) 24000f else config.fMax, freqMinHz, freqMaxHz, config.linearFreq
)

/**
 * T107: Config mit dem tatsaechlich angezeigten Frequenzbereich (nur fuer die Achsen-Beschriftung
 * im [SpectrogramOverlay]; die Mel-Parameter der DSP-Pipeline bleiben unveraendert).
 */
fun ch.etasystems.pirol.audio.dsp.SpectrogramConfig.forDisplay(
    freqMinHz: Float,
    freqMaxHz: Float
): ch.etasystems.pirol.audio.dsp.SpectrogramConfig {
    val cfgMax = if (fMax <= 0f) 24000f else fMax
    val lo = freqMinHz.coerceIn(fMin, cfgMax - 1f)
    val hi = freqMaxHz.coerceIn(lo + 1f, cfgMax)
    return copy(fMin = lo, fMax = hi)
}
