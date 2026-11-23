package ch.etasystems.pirol.data.importer

import ch.etasystems.pirol.data.repository.ImportedSource
import ch.etasystems.pirol.data.repository.RecordingSegment
import ch.etasystems.pirol.data.repository.SessionMetadata
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * T255: Planung des Fremdaudio-Imports (reines JVM): Gruppierung in Sessions, Abtastrate, Session-ID,
 * Metadaten. Die Dateien selbst schreibt [ch.etasystems.pirol.data.importer.SessionImporter].
 */
object ImportPlanner {

    /** Abtastraten, die unveraendert uebernommen werden (Pipeline resampelt je Fenster auf das Modell). */
    val KEEP_RATE_RANGE = 8_000..96_000

    /** Ersatzrate (z.B. Ultraschall 250/384 kHz, gemischte Raten in einer Session) */
    const val FALLBACK_RATE = 48_000

    /**
     * Gruppen = kuenftige Sessions. [combine] = alle Dateien in eine Session (nach Startzeit sortiert),
     * sonst je Datei eine Session (ebenfalls nach Zeit sortiert).
     */
    fun <T> groups(files: List<T>, combine: Boolean, startOf: (T) -> ZonedDateTime): List<List<T>> {
        val sorted = files.sortedBy { startOf(it).toInstant() }
        return if (combine) listOf(sorted).filter { it.isNotEmpty() } else sorted.map { listOf(it) }
    }

    /**
     * Abtastrate der Session = Rate der ersten dekodierten Datei, wenn sie in [KEEP_RATE_RANGE] liegt
     * (kein verlustbehafteter Zwischenschritt, Dateien nicht groesser als noetig), sonst [FALLBACK_RATE].
     * Spaetere Dateien mit anderer Rate werden auf diese Rate resampelt (eine Rate je Session).
     */
    fun targetRate(firstRate: Int): Int = if (firstRate in KEEP_RATE_RANGE) firstRate else FALLBACK_RATE

    /** Session-ID wie live (T155), aber mit der Aufnahmezeit statt "jetzt": sortiert sich chronologisch ein. */
    fun sessionId(start: ZonedDateTime, uuid6: String): String =
        start.toLocalDateTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss")) + "_" + uuid6

    /** Tagesordner = Aufnahmedatum (Ortszeit) */
    fun dayDir(start: ZonedDateTime): String = start.toLocalDate().toString()

    /** Session-Ende = Ende des letzten Segments (Wanduhr), sonst Start + Gesamtdauer */
    fun endedAt(start: ZonedDateTime, segments: List<RecordingSegment>, totalFrames: Long, sampleRate: Int): ZonedDateTime {
        val last = segments.lastOrNull()
        val lastStart = last?.startedAt?.let { runCatching { ZonedDateTime.parse(it) }.getOrNull() }
        return if (last != null && lastStart != null) {
            lastStart.plusNanos((last.durationSec.toDouble() * 1e9).toLong())
        } else start.plusNanos(totalFrames * 1_000_000_000L / sampleRate)
    }

    /** session.json einer importierten Session */
    fun metadata(
        sessionId: String,
        start: ZonedDateTime,
        sampleRate: Int,
        totalFrames: Long,
        segments: List<RecordingSegment>,
        latitude: Double?,
        longitude: Double?,
        confidenceThreshold: Float,
        sources: List<ImportedSource>
    ): SessionMetadata = SessionMetadata(
        sessionId = sessionId,
        startedAt = start.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        endedAt = endedAt(start, segments, totalFrames, sampleRate).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        sampleRate = sampleRate,
        latitude = latitude,
        longitude = longitude,
        confidenceThreshold = confidenceThreshold,
        totalRecordedSamples = totalFrames,
        totalDetections = 0,
        // Eine Datei = ein Segment: Liste wie live weglassen; sonst mit Wanduhr je Segment
        recordingSegments = segments.takeIf { it.size > 1 },
        importedFrom = sources
    )
}
