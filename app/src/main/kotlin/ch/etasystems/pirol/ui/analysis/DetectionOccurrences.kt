package ch.etasystems.pirol.ui.analysis

import ch.etasystems.pirol.ml.DetectionResult

/**
 * T167: Einzelvorkommen einer (geclusterten) Detektion.
 *
 * Beim Cluster-Merge (T70) bleibt nur der Anker in detections.jsonl; absorbierte Fenster
 * leben nur als CLUSTER_MERGED-Event in verifications.jsonl weiter
 * (`correctedSpecies = "merged:<id>|count:<n>|lastSeenAt:<Wanduhr-ms>[|conf:<0..1>|start:<sec>]"`;
 * `conf`/`start` ab T181, bei aelteren Sessions nicht vorhanden).
 * Ohne `start` ergibt sich die Position in der Aufnahme aus der Wanduhr-Differenz zum Anker
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
        val isAnchor: Boolean,
        /** T181: Konfidenz dieses Fensters, null = unbekannt */
        val confidence: Float? = null
    )

    /** T181: Angaben zu einem absorbierten Fenster; conf/start null bei alten Sessions. */
    data class MergeInfo(
        val timestampMs: Long,
        val confidence: Float? = null,
        val startSec: Float? = null
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

    /** T181: Liest lastSeenAt sowie optional conf und start; null, wenn lastSeenAt fehlt. */
    fun parseMergeInfo(correctedSpecies: String?): MergeInfo? {
        val ts = parseMergeTimestamp(correctedSpecies) ?: return null
        fun field(name: String): Float? = correctedSpecies!!.split('|')
            .firstOrNull { it.startsWith("$name:") }
            ?.removePrefix("$name:")?.trim()?.toFloatOrNull()
            ?.takeIf { it.isFinite() }
        return MergeInfo(
            timestampMs = ts,
            confidence = field("conf")?.takeIf { it in 0f..1f },
            startSec = field("start")?.takeIf { it >= 0f }
        )
    }

    /**
     * Vorkommen einer Detektion, zeitlich aufsteigend. Der Anker kommt immer vor.
     * Zeitpunkte vor dem Anker (unplausibel) und Duplikate werden verworfen.
     *
     * @param merges absorbierte Fenster (aus CLUSTER_MERGED); mit `start` exakte Position, sonst Wanduhr-Naeherung
     */
    fun of(detection: DetectionResult, merges: List<MergeInfo>): List<Occurrence> {
        val window = (detection.chunkEndSec - detection.chunkStartSec)
            .takeIf { it in 0.1f..30f } ?: FALLBACK_WINDOW_SEC
        val firstStart = detection.chunkStartSec.coerceAtLeast(0f)
        // Anker-Konfidenz: detection.confidence ist das Maximum ueber alle Fenster. Nur wenn kein
        // absorbiertes Fenster diesen Wert erreicht, stammt er sicher vom Anker; sonst unbekannt.
        val absorbedMax = merges.mapNotNull { it.confidence }.maxOrNull()
        // T186: liegt das beste Fenster nicht am Anfang, ist die Anker-Konfidenz des ersten Fensters unbekannt
        val bestStart = detection.bestStartSec
        val bestIsLater = bestStart != null && kotlin.math.abs(bestStart - firstStart) > BEST_TOLERANCE_SEC
        val anchorConf = if (bestIsLater) null
            else if (absorbedMax == null || detection.confidence > absorbedMax) detection.confidence else null
        val result = mutableListOf(
            Occurrence(detection.timestampMs, firstStart, firstStart + window, isAnchor = true, confidence = anchorConf)
        )
        for (m in merges.sortedBy { it.timestampMs }) {
            val ts = m.timestampMs
            if (ts - detection.timestampMs < DEDUP_TOLERANCE_MS) continue
            if (ts - result.last().timestampMs < DEDUP_TOLERANCE_MS) continue
            val start = m.startSec ?: (firstStart + (ts - detection.timestampMs) / 1000f)
            // T186: das als bestes Fenster gespeicherte Vorkommen traegt die Cluster-Konfidenz
            val conf = m.confidence
                ?: if (bestIsLater && bestStart != null && kotlin.math.abs(bestStart - start) <= BEST_TOLERANCE_SEC) detection.confidence else null
            result.add(Occurrence(ts, start, start + window, isAnchor = false, confidence = conf))
        }
        return result
    }

    /** Positionen, die weniger weit auseinanderliegen, gelten als dasselbe Fenster (Float-Rundung, %.3f). */
    private const val BEST_TOLERANCE_SEC = 0.01f

    /**
     * T186: Replay fuer Sessions ohne gespeichertes bestes Fenster. detection.confidence ist das
     * Maximum ueber alle Fenster; traegt ein CLUSTER_MERGED-Event (conf/start, T181) genau diesen
     * Wert, ist dessen Fenster das beste. Sonst (oder bei Gleichstand) bleibt das erste Fenster.
     */
    fun withBestWindow(detection: DetectionResult, merges: List<MergeInfo>): DetectionResult {
        if (detection.bestStartSec != null || detection.manualEntry) return detection
        val m = merges
            .filter { it.confidence != null && it.startSec != null }
            .filter { kotlin.math.abs(it.confidence!! - detection.confidence) <= 0.001f }
            .minByOrNull { it.timestampMs } ?: return detection
        val start = m.startSec!!
        if (kotlin.math.abs(start - detection.chunkStartSec) <= BEST_TOLERANCE_SEC) return detection
        val window = (detection.chunkEndSec - detection.chunkStartSec).takeIf { it in 0.1f..30f } ?: FALLBACK_WINDOW_SEC
        return detection.copy(bestStartSec = start, bestEndSec = start + window)
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
        val containsAnchor: Boolean,
        /** T181: Maximum der bekannten Fenster-Konfidenzen, null = keine bekannt */
        val maxConfidence: Float? = null
    ) {
        val durationSec: Float get() = endSec - startSec
    }

    /**
     * T222: Detektion, die genau eine Episode beschreibt (Wiedergabe und Teilen nutzen dafuer
     * dieselbe Ausschnitt-Logik wie fuer ganze Cluster). Wanduhr und Position gelten fuer das erste
     * Fenster der Episode, lastSeenAtMs fuer das letzte; damit enthalten Dateiname und Bildtext die Episodenzeit.
     */
    fun forEpisode(detection: DetectionResult, episode: Episode): DetectionResult = detection.copy(
        chunkStartSec = episode.startSec,
        chunkEndSec = episode.startSec + episode.windowSec,
        timestampMs = episode.timestampMs,
        lastSeenAtMs = episode.lastTimestampMs
    )

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
                    containsAnchor = c.any { it.isAnchor },
                    maxConfidence = c.mapNotNull { it.confidence }.maxOrNull()
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
