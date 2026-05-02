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
 * T78: Felder per enabledFields-Set selektierbar; Lat/Lon aus DetectionResult.
 */
object RavenExporter {

    const val FIELD_BEGIN_FILE      = "pirol_raven_field_begin_file"
    const val FIELD_BEGIN_TIME      = "pirol_raven_field_begin_time"
    const val FIELD_END_TIME        = "pirol_raven_field_end_time"
    const val FIELD_LOW_FREQ        = "pirol_raven_field_low_freq"
    const val FIELD_HIGH_FREQ       = "pirol_raven_field_high_freq"
    const val FIELD_BEGIN_DATE_TIME = "pirol_raven_field_begin_date_time"
    const val FIELD_SPECIES         = "pirol_raven_field_species"
    const val FIELD_CONFIDENCE      = "pirol_raven_field_confidence"
    const val FIELD_LAT             = "pirol_raven_field_lat"
    const val FIELD_LON             = "pirol_raven_field_lon"

    private const val LOW_FREQ_HZ = 150
    private const val HIGH_FREQ_HZ = 12000
    private const val VIEW = "Spectrogram 1"
    private const val CHANNEL = 1

    /**
     * Schreibt die Selection-Table.
     *
     * @param outputFile       Zieldatei (z.B. recording.selections.txt)
     * @param detections       Detektionen mit angewandten Verifikationen
     * @param sessionStartedAt ISO-8601-Timestamp mit Offset (aus session.json)
     * @param recordingSegments WAV-Segmente bei rotierter Session (null = Single-File)
     * @param enabledFields    Set der aktiven Feld-Keys; null = alle Felder aktiv
     * @return Anzahl geschriebener Zeilen
     */
    fun export(
        outputFile: File,
        detections: List<DetectionResult>,
        sessionStartedAt: String,
        recordingSegments: List<RecordingSegment>? = null,
        enabledFields: Set<String>? = null
    ): Int {
        val sessionStart = OffsetDateTime.parse(sessionStartedAt)
        fun on(key: String) = enabledFields == null || key in enabledFields

        outputFile.bufferedWriter(Charsets.UTF_8).use { w ->
            // Kopfzeile: Selection/View/Channel immer; optionale Felder nach Selektion
            val header = mutableListOf("Selection", "View", "Channel")
            if (on(FIELD_BEGIN_FILE))      header += "Begin File"
            if (on(FIELD_BEGIN_TIME))      header += "Begin Time (s)"
            if (on(FIELD_END_TIME))        header += "End Time (s)"
            if (on(FIELD_LOW_FREQ))        header += "Low Freq (Hz)"
            if (on(FIELD_HIGH_FREQ))       header += "High Freq (Hz)"
            if (on(FIELD_BEGIN_DATE_TIME)) header += "Begin Date Time"
            if (on(FIELD_SPECIES))         { header += "Species"; header += "Common Name" }
            if (on(FIELD_CONFIDENCE))      header += "Confidence"
            if (on(FIELD_LAT))             header += "Lat"
            if (on(FIELD_LON))             header += "Lon"
            header += "Status"
            header += "Corrected Species"
            w.appendLine(header.joinToString("\t"))

            detections.forEachIndexed { index, d ->
                val segment = recordingSegments?.firstOrNull {
                    d.chunkStartSec >= it.startSec && d.chunkStartSec < it.startSec + it.durationSec
                }
                val beginFile  = segment?.fileName ?: "recording.wav"
                val localStart = if (segment != null) d.chunkStartSec - segment.startSec else d.chunkStartSec
                val localEnd   = if (segment != null) d.chunkEndSec   - segment.startSec else d.chunkEndSec
                val detectionTime = sessionStart.plusSeconds(d.chunkStartSec.toLong())
                val beginDateTime = detectionTime.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

                val row = mutableListOf((index + 1).toString(), VIEW, CHANNEL.toString())
                if (on(FIELD_BEGIN_FILE))      row += sanitize(beginFile)
                if (on(FIELD_BEGIN_TIME))      row += String.format(Locale.US, "%.3f", localStart)
                if (on(FIELD_END_TIME))        row += String.format(Locale.US, "%.3f", localEnd)
                if (on(FIELD_LOW_FREQ))        row += LOW_FREQ_HZ.toString()
                if (on(FIELD_HIGH_FREQ))       row += HIGH_FREQ_HZ.toString()
                if (on(FIELD_BEGIN_DATE_TIME)) row += beginDateTime
                if (on(FIELD_SPECIES))         { row += sanitize(d.scientificName); row += sanitize(d.commonName) }
                if (on(FIELD_CONFIDENCE))      row += String.format(Locale.US, "%.3f", d.confidence)
                if (on(FIELD_LAT))             row += d.latitude?.let  { String.format(Locale.US, "%.6f", it) } ?: ""
                if (on(FIELD_LON))             row += d.longitude?.let { String.format(Locale.US, "%.6f", it) } ?: ""
                row += d.verificationStatus.name
                row += sanitize(d.correctedSpecies ?: "")
                w.appendLine(row.joinToString("\t"))
            }
        }
        return detections.size
    }

    /** Tabs/Newlines aus Strings entfernen, damit das TSV-Format nicht bricht. */
    private fun sanitize(s: String): String =
        s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim()
}
