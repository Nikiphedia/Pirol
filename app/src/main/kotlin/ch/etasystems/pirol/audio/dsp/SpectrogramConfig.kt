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
 * @param refDb Referenzpegel fuer dB-Skalierung
 * @param minDb Untere Grenze (Clipping) in dB
 */
data class SpectrogramConfig(
    val fftSize: Int = 2048,
    val hopSize: Int = 512,
    val numMelBins: Int = 128,
    val fMin: Float = 0f,
    val fMax: Float = -1f,
    val refDb: Float = 80f,
    val minDb: Float = -80f
) {
    companion object {
        /** Voegel: 125-8000 Hz, feines Frequenzraster */
        val BIRDS = SpectrogramConfig(fMin = 125f, fMax = 8000f)

        /** Fledermaeuse: breites Spektrum bis Nyquist */
        val BATS = SpectrogramConfig(fftSize = 1024, hopSize = 256, fMin = 10000f, fMax = -1f)

        /** Breitband: gesamtes Spektrum */
        val WIDEBAND = SpectrogramConfig(fMin = 0f, fMax = -1f)
    }
}
