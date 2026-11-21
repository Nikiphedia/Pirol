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

    /**
     * T171: Zusammenhaengende Fenster (Dauersang, Overlap) = eine Episode.
     *
     * Luecken-Toleranz: Der Abstand der Startzeiten zweier Folgefenster darf hoechstens
     * Fensterlaenge + [EPISODE_JITTER_SEC] betragen. Das deckt alle Hops (0 / 50 / 67 % Overlap,
     * Hop <= Fensterlaenge) ab und faengt Wanduhr-Jitter ab (Zeitstempel sind Wanduhr, die Position
     * wird daraus abgeleitet). Die Trennung zwischen Karten bleibt die Cluster-Regel (T70).
     */
    const val EPISODE_JITTER_SEC = 1.0f

    /**
     * Eine Episode: lueckenlose Folge von Fenstern.
     * @param timestampMs Wanduhr des ersten Fensters
     * @param lastTimestampMs Wanduhr des letzten Fensters
     * @param startSec Beginn des ersten Fensters in der Session-Aufnahme
     * @param endSec Ende des letzten Fensters
     * @param windowSec Laenge eines Fensters
     */
    data class Episode(
        val timestampMs: Long,
        val lastTimestampMs: Long,
        val startSec: Float,
        val endSec: Float,
        val windowSec: Float,
        val windowCount: Int,
        val containsAnchor: Boolean
    ) {
        val durationSec: Float get() = endSec - startSec
    }

    /** Fasst Vorkommen (beliebige Reihenfolge) zu Episoden zusammen, zeitlich aufsteigend. */
    fun episodes(occurrences: List<Occurrence>): List<Episode> {
        val result = mutableListOf<Episode>()
        var cur: MutableList<Occurrence>? = null
        fun flush() {
            val c = cur ?: return
            val first = c.first()
            val last = c.last()
            result.add(
                Episode(
                    timestampMs = first.timestampMs,
                    lastTimestampMs = last.timestampMs,
                    startSec = first.startSec,
                    endSec = last.endSec,
                    windowSec = first.endSec - first.startSec,
                    windowCount = c.size,
                    containsAnchor = c.any { it.isAnchor }
                )
            )
        }
        for (occ in occurrences.sortedBy { it.startSec }) {
            val c = cur
            val prev = c?.last()
            if (c != null && prev != null &&
                occ.startSec - prev.startSec <= (prev.endSec - prev.startSec) + EPISODE_JITTER_SEC
            ) {
                c.add(occ)
            } else {
                flush()
                cur = mutableListOf(occ)
            }
        }
        flush()
        return result
    }
}
