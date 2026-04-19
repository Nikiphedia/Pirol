package ch.etasystems.pirol.data.export

import ch.etasystems.pirol.ml.DetectionResult
import java.io.File
import java.util.Locale

/**
 * Exportiert Detektionen einer Session als Raven Selection Table (.txt).
 *
 * Format: Tab-getrennt, eine Header-Zeile + eine Zeile pro Detektion.
 * Kompatibel mit Cornell Raven, Audacity (Label-Track), Sonic Visualiser.
 */
object RavenExporter {

    private const val LOW_FREQ_HZ = 0
    private const val HIGH_FREQ_HZ = 12000
    private const val VIEW = "Spectrogram 1"
    private const val CHANNEL = 1

    private val HEADER = listOf(
        "Selection", "View", "Channel",
        "Begin Time (s)", "End Time (s)",
        "Low Freq (Hz)", "High Freq (Hz)",
        "Species", "Common Name", "Confidence",
        "Status", "Corrected Species"
    ).joinToString("\t")

    /**
     * Schreibt die Selection-Table.
     *
     * @param outputFile Zieldatei (z.B. recording.selections.txt)
     * @param detections Detektionen mit angewandten Verifikationen
     * @return Anzahl geschriebener Zeilen
     */
    fun export(outputFile: File, detections: List<DetectionResult>): Int {
        outputFile.bufferedWriter(Charsets.UTF_8).use { w ->
            w.appendLine(HEADER)
            detections.forEachIndexed { index, d ->
                val row = listOf(
                    (index + 1).toString(),
                    VIEW,
                    CHANNEL.toString(),
                    String.format(Locale.US, "%.3f", d.chunkStartSec),
                    String.format(Locale.US, "%.3f", d.chunkEndSec),
                    LOW_FREQ_HZ.toString(),
                    HIGH_FREQ_HZ.toString(),
                    sanitize(d.scientificName),
                    sanitize(d.commonName),
                    String.format(Locale.US, "%.3f", d.confidence),
                    d.verificationStatus.name,
                    sanitize(d.correctedSpecies ?: "")
                ).joinToString("\t")
                w.appendLine(row)
            }
        }
        return detections.size
    }

    /** Tabs/Newlines aus Strings entfernen, damit das TSV-Format nicht bricht. */
    private fun sanitize(s: String): String =
        s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim()
}
