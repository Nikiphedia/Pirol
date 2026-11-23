package ch.etasystems.pirol.audio.dsp

/**
 * Konfiguration fuer Spektrogramm-Berechnung.
 * Presets fuer verschiedene Tiergruppen.
 *
 * @param fftSize FFT-Fenstergroesse in Samples (muss Zweierpotenz sein)
 * @param hopSize Schrittweite zwischen aufeinanderfolgenden Fenstern
 * @param numMelBins Anzahl Mel-Filterbank-Ausgaenge
 * @param fMin Untere Grenzfrequenz in Hz
 * @param fMax Obere Grenzfrequenz in Hz (-1 = Nyquist)
 * @param minDb Rueckfall-Untergrenze der Anzeige in dBFS (T199)
 */
data class SpectrogramConfig(
    val fftSize: Int = 2048,
    val hopSize: Int = 512,
    val numMelBins: Int = 128,
    val fMin: Float = 0f,
    val fMax: Float = -1f,
    val minDb: Float = SONOGRAM_DEFAULT_MIN_DBFS
) {
    companion object {
        /** Voegel: 125-12000 Hz, feines Frequenzraster */
        val BIRDS = SpectrogramConfig(fMin = 125f, fMax = 12000f)

        /** Fledermaeuse: breites Spektrum bis Nyquist */
        val BATS = SpectrogramConfig(fftSize = 1024, hopSize = 256, fMin = 10000f, fMax = -1f)

        /** Breitband: gesamtes Spektrum */
        val WIDEBAND = SpectrogramConfig(fMin = 0f, fMax = -1f)
    }
}

/**
 * T199: Sonogramm-Pegel sind dBFS (Vollaussteuerungs-Sinus = 0 dBFS, wie [SpectrumAnalyzer]).
 * Boden -140 dBFS liegt unter dem 16-bit-Quantisierungsrauschen (~ -115..-125 dBFS pro Mel-Band),
 * digitale Stille landet dort.
 */
const val MEL_FLOOR_DBFS = -140f

/**
 * T199: Standard-Anzeigebereich (gemeinsam fuer Sonogramm, Spektrum, Oszillogramm).
 * Unten -110: knapp ueber dem 16-bit-Rauschboden, Mikrofon-Eigenrauschen (~ -100) erscheint dunkel.
 * Oben -20: Vogelrufe im Feld erreichen selten mehr; entspricht etwa dem alten relativen Bereich
 * [-80, 0] (= -94..-14 dBFS) mit mehr Raum fuer leise Anteile.
 */
const val SONOGRAM_DEFAULT_MIN_DBFS = -110f
const val SONOGRAM_DEFAULT_MAX_DBFS = -20f
