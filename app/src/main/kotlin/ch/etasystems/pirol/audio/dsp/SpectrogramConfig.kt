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
 * @param linearFreq T294: Baender linear in Hz statt Mel (Ultraschall-Sessions)
 */
data class SpectrogramConfig(
    val fftSize: Int = 2048,
    val hopSize: Int = 512,
    val numMelBins: Int = 128,
    val fMin: Float = 0f,
    val fMax: Float = -1f,
    val minDb: Float = SONOGRAM_DEFAULT_MIN_DBFS,
    val linearFreq: Boolean = false
) {
    companion object {
        /**
         * Voegel. T200: Mel bis Nyquist (0-24000 Hz bei 48 kHz), die Anzeige schneidet den
         * gemeinsamen Frequenzbereich zu. 168 statt 128 Baender halten den Mel-Abstand von vorher
         * (125-12000 Hz / 128 Baender = 23.9 Mel, jetzt 0-24000 Hz / 168 = 23.8 Mel) -> gleiche
         * Aufloesung im Vogelbereich.
         */
        val BIRDS = SpectrogramConfig(numMelBins = 168, fMin = 0f, fMax = -1f)

        /** Fledermaeuse: breites Spektrum bis Nyquist */
        val BATS = SpectrogramConfig(fftSize = 1024, hopSize = 256, fMin = 10000f, fMax = -1f)

        /** Breitband: gesamtes Spektrum */
        val WIDEBAND = SpectrogramConfig(fMin = 0f, fMax = -1f)

        /**
         * T294: Ultraschall-Sessions (Rate > [ULTRASOUND_MIN_RATE_EXCLUSIVE]). Lineare Frequenzachse bis Nyquist:
         * auf der Mel-Skala waere 10-20 kHz so breit wie 100-150 kHz. 256 Baender = 750 Hz je Band bei 384 kHz.
         */
        val ULTRASOUND = SpectrogramConfig(numMelBins = 256, fMin = 0f, fMax = -1f, linearFreq = true)

        /** T294: ab hier (exklusiv) gilt eine Session als Ultraschall-Session (Anzeige, Wiedergabe, Raven) */
        const val ULTRASOUND_MIN_RATE_EXCLUSIVE = 96_000

        /** T294: Standard-Anzeigebereich einer Ultraschall-Session in Hz */
        const val ULTRASOUND_DISPLAY_MIN_HZ = 10_000f
        const val ULTRASOUND_DISPLAY_MAX_HZ = 150_000f

        /** T294: Ultraschall-Session? (192-500 kHz; 48/96 kHz nein) */
        fun isUltrasoundRate(sampleRate: Int): Boolean = sampleRate > ULTRASOUND_MIN_RATE_EXCLUSIVE

        /** T294: Anzeige-Config der Analyse fuer eine Session-Rate (bis 96 kHz unveraendert [BIRDS]). */
        fun forSessionRate(sampleRate: Int): SpectrogramConfig =
            if (isUltrasoundRate(sampleRate)) ULTRASOUND else BIRDS
    }

    /** T200: Obergrenze in Hz bei [sampleRate] (fMax <= 0 = Nyquist, nie ueber Nyquist). */
    fun maxHz(sampleRate: Int): Float {
        val nyquist = sampleRate / 2f
        return if (fMax <= 0f) nyquist else fMax.coerceAtMost(nyquist)
    }

    /** T200: Config mit aufgeloester Obergrenze (fuer Achse, Bin-Zuschnitt und Regler). */
    fun resolved(sampleRate: Int): SpectrogramConfig = copy(fMax = maxHz(sampleRate))
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
