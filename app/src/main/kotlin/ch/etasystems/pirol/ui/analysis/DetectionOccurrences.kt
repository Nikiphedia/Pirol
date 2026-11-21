package ch.etasystems.pirol.ui.analysis

import ch.etasystems.pirol.ml.DetectionResult

/**
 * T167: Einzelvorkommen einer (geclusterten) Detektion.
 *
 * Beim Cluster-Merge (T70) bleibt nur der Anker in detections.jsonl; absorbierte Fenster
 * leben nur als CLUSTER_MERGED-Event in verifications.jsonl weiter
 * (`correctedSpecies = "merged:<id>|count:<n>|lastSeenAt:<Wanduhr-ms>"`).
 * Aus der Wanduhr-Zeit ergibt sich die Position in der Aufnahme ueber die Differenz zum Anker
 * (gleiche Regel wie [ch.etasystems.pirol.audio.export.DetectionExcerpt.compute]).
 *
 * Reine Funktionen ohne Android-Abhaengigkeit (unit-testbar).
 */
object DetectionOccurrences {

    /** Fensterlaenge, falls chunkEnd/chunkStart unplausibel sind */
    private const val FALLBACK_WINDOW_SEC = 3f

    /** Zeitpunkte, die naeher beieinander liegen, gelten als dasselbe Fenster (Overlap-Doppler). */
    private const val DEDUP_TOLERANCE_MS = 500L

    /**
     * Ein Vorkommen: Fenster in der Session-Aufnahme.
     * @param isAnchor true = das gespeicherte Fenster der Detektion (nur dort ist die Konfidenz bekannt)
     */
    data class Occurrence(
        val timestampMs: Long,
        val startSec: Float,
        val endSec: Float,
        val isAnchor: Boolean
    )

    /** Liest `lastSeenAt:<ms>` aus dem correctedSpecies-Feld eines CLUSTER_MERGED-Events. */
    fun parseMergeTimestamp(correctedSpecies: String?): Long? {
        if (correctedSpecies.isNullOrBlank()) return null
        return correctedSpecies.split('|')
            .firstOrNull { it.startsWith("lastSeenAt:") }
            ?.removePrefix("lastSeenAt:")
            ?.trim()
            ?.toLongOrNull()
    }

    /**
     * Vorkommen einer Detektion, zeitlich aufsteigend. Der Anker kommt immer vor.
     * Zeitpunkte vor dem Anker (unplausibel) und Duplikate werden verworfen.
     *
     * @param mergeTimestampsMs Wanduhr-Zeiten der absorbierten Fenster (aus CLUSTER_MERGED)
     */
    fun of(detection: DetectionResult, mergeTimestampsMs: List<Long>): List<Occurrence> {
        val window = (detection.chunkEndSec - detection.chunkStartSec)
            .takeIf { it in 0.1f..30f } ?: FALLBACK_WINDOW_SEC
        val firstStart = detection.chunkStartSec.coerceAtLeast(0f)
        val result = mutableListOf(
            Occurrence(detection.timestampMs, firstStart, firstStart + window, isAnchor = true)
        )
        for (ts in mergeTimestampsMs.sorted()) {
            if (ts - detection.timestampMs < DEDUP_TOLERANCE_MS) continue
            if (ts - result.last().timestampMs < DEDUP_TOLERANCE_MS) continue
            val start = firstStart + (ts - detection.timestampMs) / 1000f
            result.add(Occurrence(ts, start, start + window, isAnchor = false))
        }
        return result
    }
}
