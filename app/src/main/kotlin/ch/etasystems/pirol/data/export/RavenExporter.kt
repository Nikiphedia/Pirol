package ch.etasystems.pirol.data.export

import ch.etasystems.pirol.data.repository.RecordingSegment
import ch.etasystems.pirol.ml.DetectionResult
import java.io.File
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Exportiert Detektionen einer Session als Raven Selection Table (.txt).
 *
 * Format: Tab-getrennt, eine Header-Zeile + eine Zeile pro Detektion.
 * Kompatibel mit Cornell Raven, Audacity (Label-Track), Sonic Visualiser.
 *
 * T57-B3: Neue Spalten Begin File (fuer Multi-File-Sessions) und Begin Date Time.
 * Low Freq auf 150 Hz (realistischer Vogelgesang-Bereich).
 */
object RavenExporter {

    private const val LOW_FREQ_HZ = 150   // T57-B3: war 0; TODO V0.0.7: per-species (T60-Follow)
    private const val HIGH_FREQ_HZ = 12000
    private const val VIEW = "Spectrogram 1"
    private const val CHANNEL = 1

    private val HEADER = listOf(
        "Selection", "View", "Channel",
        "Begin File",
        "Begin Time (s)", "End Time (s)",
        "Low Freq (Hz)", "High Freq (Hz)",
        "Begin Date Time",
        "Species", "Common Name", "Confidence",
        "Status", "Corrected Species"
    ).joinToString("\t")

    /**
     * Schreibt die Selection-Table.
     *
     * @param outputFile Zieldatei (z.B. recording.selections.txt)
     * @param detections Detektionen mit angewandten Verifikationen
     * @param sessionStartedAt ISO-8601-Timestamp mit Offset (aus session.json)
     * @param recordingSegments WAV-Segmente bei rotierter Session (null = Single-File)
     * @return Anzahl geschriebener Zeilen
     */
    fun export(
        outputFile: File,
        detections: List<DetectionResult>,
        sessionStartedAt: String,
        recordingSegments: List<RecordingSegment>? = null
    ): Int {
        val sessionStart = OffsetDateTime.parse(sessionStartedAt)

        outputFile.bufferedWriter(Charsets.UTF_8).use { w ->
            w.appendLine(HEADER)
            detections.forEachIndexed { index, d ->
                // Begin File + lokale Zeit bestimmen (T57-B3: Multi-File-Support)
                val segment = recordingSegments?.firstOrNull {
                    d.chunkStartSec >= it.startSec && d.chunkStartSec < it.startSec + it.durationSec
                }
                val beginFile = segment?.fileName ?: "recording.wav"
                val localStart = if (segment != null) d.chunkStartSec - segment.startSec else d.chunkStartSec
                val localEnd   = if (segment != null) d.chunkEndSec   - segment.startSec else d.chunkEndSec

                // Begin Date Time: Session-Start + chunkStartSec (Sekunden-Genauigkeit reicht)
                val detectionTime = sessionStart.plusSeconds(d.chunkStartSec.toLong())
                val beginDateTime = detectionTime.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

                val row = listOf(
                    (index + 1).toString(),
                    VIEW,
                    CHANNEL.toString(),
                    sanitize(beginFile),
                    String.format(Locale.US, "%.3f", localStart),
                    String.format(Locale.US, "%.3f", localEnd),
                    LOW_FREQ_HZ.toString(),
                    HIGH_FREQ_HZ.toString(),
                    beginDateTime,
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
