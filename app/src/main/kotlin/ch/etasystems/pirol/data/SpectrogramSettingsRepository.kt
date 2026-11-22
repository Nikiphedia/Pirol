package ch.etasystems.pirol.data

import ch.etasystems.pirol.ui.components.SpectrogramDisplaySettings
import ch.etasystems.pirol.ui.components.SpectrogramPalette
import ch.etasystems.pirol.ui.components.SpectrogramPreset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * T107: Einzige Quelle der Sonogramm-Darstellungs-Einstellungen (Koin-Singleton).
 *
 * Live-Tab, Analyse-Tab, Sonogramm-Panel und Settings lesen denselben [settings]-Flow
 * und schreiben ueber [update]. Persistenz ueber die bestehenden `pirol_spectrogram_*`-Keys
 * in [AppPreferences] (keine Migration noetig).
 */
class SpectrogramSettingsRepository(private val prefs: AppPreferences) {

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<SpectrogramDisplaySettings> = _settings.asStateFlow()

    /** Aendert die Einstellungen, publiziert sofort und schreibt in die Prefs. */
    fun update(transform: (SpectrogramDisplaySettings) -> SpectrogramDisplaySettings) {
        val new = transform(_settings.value)
        if (new == _settings.value) return
        _settings.value = new
        save(new)
    }

    fun applyPreset(preset: SpectrogramPreset) = update { it.withPreset(preset) }

    private fun load(): SpectrogramDisplaySettings {
        val palette = try {
            SpectrogramPalette.valueOf(prefs.paletteName)
        } catch (_: IllegalArgumentException) {
            SpectrogramPalette.MAGMA
        }
        return SpectrogramDisplaySettings(
            autoContrast = prefs.spectrogramAutoContrast,
            minDb = prefs.spectrogramMinDb,
            maxDb = prefs.spectrogramMaxDb,
            gamma = prefs.spectrogramGamma,
            ceilingDb = prefs.spectrogramCeilingDb,
            palette = palette,
            freqMinHz = prefs.spectrogramFreqMinHz,
            freqMaxHz = prefs.spectrogramFreqMaxHz,
            windowSec = prefs.spectrogramWindowSec,
            autoLowPct = prefs.spectrogramAutoLowPct,
            autoHighPct = prefs.spectrogramAutoHighPct,
            autoMinSpanDb = prefs.spectrogramAutoMinSpanDb,
            displayMode = try {
                ch.etasystems.pirol.ui.components.LiveDisplayMode.valueOf(prefs.liveDisplayMode)
            } catch (_: IllegalArgumentException) {
                ch.etasystems.pirol.ui.components.LiveDisplayMode.SONOGRAM
            }
        )
    }

    private fun save(s: SpectrogramDisplaySettings) {
        prefs.spectrogramAutoContrast = s.autoContrast
        prefs.spectrogramMinDb = s.minDb
        prefs.spectrogramMaxDb = s.maxDb
        prefs.spectrogramGamma = s.gamma
        prefs.spectrogramCeilingDb = s.ceilingDb
        prefs.paletteName = s.palette.name
        prefs.spectrogramFreqMinHz = s.freqMinHz
        prefs.spectrogramFreqMaxHz = s.freqMaxHz
        prefs.spectrogramWindowSec = s.windowSec
        prefs.spectrogramAutoLowPct = s.autoLowPct
        prefs.spectrogramAutoHighPct = s.autoHighPct
        prefs.spectrogramAutoMinSpanDb = s.autoMinSpanDb
        prefs.liveDisplayMode = s.displayMode.name
    }
}
