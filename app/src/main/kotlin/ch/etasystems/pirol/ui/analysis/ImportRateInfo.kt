package ch.etasystems.pirol.ui.analysis

import ch.etasystems.pirol.audio.dsp.SpectrogramConfig
import ch.etasystems.pirol.data.importer.ImportPlanner
import java.util.Locale

/**
 * T294: Zusatzzeile im Import-Dialog: erkannte Zeitdehnung (GUANO TE) mit wahrer Rate, oder Ultraschall-Rate.
 * null = nichts anzeigen (normale Aufnahme bis 96 kHz).
 */
internal sealed class ImportRateInfo {
    data class TimeExpanded(val factor: Int, val trueRateKhz: String) : ImportRateInfo()
    data class Ultrasound(val rateKhz: String) : ImportRateInfo()

    companion object {
        fun of(fileRate: Int, timeExpansion: Int): ImportRateInfo? {
            val rate = ImportPlanner.effectiveRate(fileRate, timeExpansion)
            return when {
                timeExpansion > 1 -> TimeExpanded(timeExpansion, khz(rate))
                SpectrogramConfig.isUltrasoundRate(rate) -> Ultrasound(khz(rate))
                else -> null
            }
        }

        /** kHz ohne unnoetige Nachkommastellen: 384000 -> "384", 312500 -> "312.5" */
        fun khz(rate: Int): String {
            val k = rate / 1000.0
            return if (k == Math.floor(k)) k.toLong().toString() else String.format(Locale.US, "%.1f", k)
        }
    }
}
