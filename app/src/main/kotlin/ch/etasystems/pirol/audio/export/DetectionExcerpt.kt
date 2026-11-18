package ch.etasystems.pirol.audio.export

import ch.etasystems.pirol.ml.DetectionResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * T116: Ausschnitt einer Detektion in recording.wav (Wiedergabe, Zoom und Export teilen diese Logik).
 *
 * Reine Funktionen ohne Android-Abhaengigkeit (unit-testbar).
 */
object DetectionExcerpt {

    /** Kontext vor dem Beginn der Detektion */
    const val PRE_ROLL_SEC = 3f

    /** Kontext nach dem Ende der Detektion */
    const val POST_ROLL_SEC = 5f

    /** Standard fuer die maximale Ausschnittlaenge */
    const val DEFAULT_MAX_SEC = 30f
    const val MIN_MAX_SEC = 10f
    const val MAX_MAX_SEC = 120f

    /** Fensterlaenge, falls chunkEnd/chunkStart unplausibel sind (BirdNET-Chunk) */
    private const val FALLBACK_WINDOW_SEC = 3f

    /**
     * T128: true, wenn die Detektion eine Position in recording.wav hat. Manuelle Eintraege
     * werden mit chunkStart = chunkEnd = 0 angelegt und haben keine; sie sind nicht teilbar.
     */
    fun hasAudioPosition(d: DetectionResult): Boolean = !d.manualEntry || d.chunkEndSec > d.chunkStartSec

    /**
     * Zeitbereich in recording.wav.
     * @param startSec Beginn des Ausschnitts
     * @param endSec Ende des Ausschnitts
     * @param detectionStartSec Beginn der Detektion (erstes Fenster) innerhalb der Datei
     */
    data class Range(val startSec: Float, val endSec: Float, val detectionStartSec: Float) {
        val durationSec: Float get() = endSec - startSec
    }

    /**
     * Berechnet den Ausschnitt einer (ggf. geclusterten) Detektion.
     *
     * Detektion = erstes Fenster ([DetectionResult.chunkStartSec]) bis Ende des letzten Fensters.
     * Der Start des letzten Fensters ergibt sich aus der Wanduhr-Differenz
     * `lastSeenAtMs - timestampMs` (T110: timestampMs = Wanduhr des Fensterstarts im WAV).
     * Dazu 3 s davor und 5 s danach; auf [0, fileDurationSec] begrenzt; laenger als [maxSec]
     * wird hinten gekappt (Start mit Vorlauf bleibt).
     *
     * @param fileDurationSec Dauer der WAV (<= 0 = unbekannt, dann keine Begrenzung am Ende)
     */
    fun compute(
        detection: DetectionResult,
        fileDurationSec: Float,
        maxSec: Float = DEFAULT_MAX_SEC
    ): Range {
        val window = (detection.chunkEndSec - detection.chunkStartSec)
            .takeIf { it in 0.1f..30f } ?: FALLBACK_WINDOW_SEC
        val firstStart = detection.chunkStartSec.coerceAtLeast(0f)
        val lastOffset = ((detection.lastSeenAtMs - detection.timestampMs) / 1000f).coerceAtLeast(0f)
        val detEnd = firstStart + lastOffset + window
        return computeRange(firstStart, detEnd, fileDurationSec, maxSec)
    }

    /** Kern der Berechnung auf Sekunden-Basis (Test-freundlich). */
    fun computeRange(
        detectionStartSec: Float,
        detectionEndSec: Float,
        fileDurationSec: Float,
        maxSec: Float = DEFAULT_MAX_SEC
    ): Range {
        val cap = maxSec.coerceAtLeast(PRE_ROLL_SEC + 1f)
        var start = (detectionStartSec - PRE_ROLL_SEC).coerceAtLeast(0f)
        var end = detectionEndSec + POST_ROLL_SEC
        if (end - start > cap) end = start + cap
        if (fileDurationSec > 0f) {
            end = end.coerceAtMost(fileDurationSec)
            if (start > end - 0.1f) start = (end - 0.1f).coerceAtLeast(0f)
        }
        if (end < start) end = start
        return Range(start, end, detectionStartSec)
    }

    /**
     * Dateiname `{yyyy-MM-dd}_{HHmmss}_{Genus_species}` ohne Endung, ASCII.
     * @param timestampMs Wanduhr der Detektion
     * @param timeZone Lokale Zeitzone (Standard: Geraete-Zone)
     */
    fun baseFileName(
        scientificName: String,
        timestampMs: Long,
        timeZone: TimeZone = TimeZone.getDefault()
    ): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.ROOT).apply { this.timeZone = timeZone }
        val species = scientificName.trim()
            .replace(Regex("[^A-Za-z0-9]+"), "_")
            .trim('_')
            .ifEmpty { "Unbekannt" }
        return "${fmt.format(Date(timestampMs))}_$species"
    }
}
