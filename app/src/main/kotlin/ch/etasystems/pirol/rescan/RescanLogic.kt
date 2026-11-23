package ch.etasystems.pirol.rescan

import ch.etasystems.pirol.ml.DetectionResult
import ch.etasystems.pirol.ml.OverlapFallback
import ch.etasystems.pirol.ml.SpeciesKey
import ch.etasystems.pirol.ml.VerificationEvent
import ch.etasystems.pirol.ml.VerificationStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * T221: Dateinamen einer Rescan-Variante (reine Logik, JVM-testbar).
 *
 * Kennung `<id>` = lokale Startzeit des Rescans `yyyyMMdd-HHmmss` (bei Kollision `-2`, `-3` ...).
 * Bewusst ohne Modell-/Listen-ID im Namen: Listen-IDs enthalten `:` (z.B. `list:beispiel`), das auf
 * FAT/SAF-Speichern unzulaessig ist; die Zeit ist eindeutig, sortierbar und kurz. Modell, Liste,
 * Schwelle und Overlap stehen in `rescan-<id>.json`.
 *
 * Dateien der Variante (neben dem Original, das nie veraendert wird):
 * - `detections.rescan-<id>.jsonl` — Detektionen des Rescans
 * - `verifications.rescan-<id>.jsonl` — Cluster-Merges, uebernommene und spaetere Verifikationen
 * - `recording.rescan-<id>.selections.txt` — Raven-Tabelle der Variante
 * - `rescan-<id>.json` — Einstellungen; wird zuletzt geschrieben (Variante erst dann sichtbar)
 */
object RescanFiles {
    const val DETECTIONS = "detections.jsonl"
    const val VERIFICATIONS = "verifications.jsonl"
    const val RAVEN = "recording.selections.txt"

    private val META_REGEX = Regex("""rescan-(\d{8}-\d{6}(?:-\d+)?)\.json""")
    private val ID_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    fun detections(id: String?): String = if (id == null) DETECTIONS else "detections.rescan-$id.jsonl"
    fun verifications(id: String?): String = if (id == null) VERIFICATIONS else "verifications.rescan-$id.jsonl"
    fun raven(id: String?): String = if (id == null) RAVEN else "recording.rescan-$id.selections.txt"
    fun meta(id: String): String = "rescan-$id.json"

    /** Alle Dateien einer Variante; Metadaten zuerst (beim Loeschen verschwindet die Variante sofort). */
    fun filesOf(id: String): List<String> = listOf(meta(id), detections(id), verifications(id), raven(id))

    /** Kennungen aller Varianten im Ordner (aus den Metadaten-Dateien), aufsteigend. */
    fun idsIn(names: Collection<String>): List<String> =
        names.mapNotNull { META_REGEX.matchEntire(it)?.groupValues?.get(1) }.sorted()

    /** Neue Kennung aus der lokalen Zeit; bei Kollision mit [existing] Zaehler anhaengen. */
    fun newId(nowMs: Long, zone: ZoneId, existing: Collection<String>): String {
        val base = Instant.ofEpochMilli(nowMs).atZone(zone).format(ID_FORMAT)
        if (base !in existing) return base
        var n = 2
        while ("$base-$n" in existing) n++
        return "$base-$n"
    }
}

/** T221: Fensterplan, Zeitbasis und Position fuer den Rescan (reine Logik). */
object RescanPlan {

    /**
     * Startframes der Analysefenster ueber die ganze Session (alle Segmente als eine Achse, inkl. Preroll).
     * Raster wie live ([ch.etasystems.pirol.ml.ChunkAccumulator]): 0, hop, 2*hop ...; nur volle Fenster.
     * T231: [maxHopMs] wie live (Perch 5 s: Hop 3 s).
     */
    fun windowStarts(totalFrames: Long, sampleRate: Int, windowMs: Int, overlapPercent: Int, maxHopMs: Int = Int.MAX_VALUE): List<Long> {
        if (sampleRate <= 0 || windowMs <= 0) return emptyList()
        val windowFrames = sampleRate.toLong() * windowMs / 1000
        val hopFrames = sampleRate.toLong() * OverlapFallback.hopMsFor(overlapPercent, windowMs, maxHopMs) / 1000
        if (windowFrames <= 0 || hopFrames <= 0) return emptyList()
        val starts = ArrayList<Long>()
        var s = 0L
        while (s + windowFrames <= totalFrames) {
            starts.add(s)
            s += hopFrames
        }
        return starts
    }

    /**
     * Wanduhr (Epoch ms) von Sekunde 0 der Session-Achse. Aus den Original-Detektionen abgeleitet
     * (Median von timestampMs - chunkStartSec; deckt Preroll und Segmente ab), sonst [startedAtMs].
     */
    fun epochAtZero(originals: List<DetectionResult>, startedAtMs: Long?): Long? {
        val offsets = originals.filter { !it.manualEntry }
            .map { it.timestampMs - Math.round(it.chunkStartSec.toDouble() * 1000.0) }
            .sorted()
        if (offsets.isNotEmpty()) return offsets[offsets.size / 2]
        return startedAtMs
    }

    /** Position zur Zeit [timeMs]: zeitlich naechster Routenpunkt (time ms, lat, lon), sonst [fallback]. */
    fun positionAt(timeMs: Long, track: List<Triple<Long, Double, Double>>, fallback: Pair<Double, Double>?): Pair<Double, Double>? {
        val p = track.minByOrNull { kotlin.math.abs(it.first - timeMs) } ?: return fallback
        return p.second to p.third
    }
}

/**
 * T221: Verifikationen des Originals auf die Rescan-Detektionen uebertragen (reine Logik).
 *
 * Regel: gleiche Art (Vergleich ueber [SpeciesKey]) und ueberlappendes Zeitfenster. Uebernommen wird
 * der Status der Original-Detektion mit der groessten Ueberlappung (bei Gleichstand die zuletzt
 * verifizierte). Wurde im Original zu einer Art korrigiert, die der Rescan direkt erkennt, gilt das
 * als Bestaetigung. Ergebnis sind Verifikations-Events fuer die Datei der Variante (newValue nennt
 * die Herkunft); die Detektionszeilen selbst bleiben Rohdaten.
 */
object RescanVerification {

    const val SOURCE_PREFIX = "rescan_from:"

    /** Zeitspanne einer Detektion inkl. Cluster (erstes Fenster bis letztes erfasstes Fenster). */
    fun span(d: DetectionResult): Pair<Float, Float> {
        val windowSec = (d.chunkEndSec - d.chunkStartSec).coerceAtLeast(0f)
        val clusterSec = ((d.lastSeenAtMs - d.timestampMs).coerceAtLeast(0L)) / 1000f
        return d.chunkStartSec to maxOf(d.chunkEndSec, d.chunkStartSec + clusterSec + windowSec)
    }

    /** Ueberlappung in Sekunden (0 = keine; Beruehren zaehlt nicht). */
    fun overlapSec(a: DetectionResult, b: DetectionResult): Float {
        val (aS, aE) = span(a)
        val (bS, bE) = span(b)
        return (minOf(aE, bE) - maxOf(aS, bS)).coerceAtLeast(0f)
    }

    fun transfer(rescan: List<DetectionResult>, originals: List<DetectionResult>, nowMs: Long): List<VerificationEvent> {
        val verified = originals.filter { it.verificationStatus != VerificationStatus.UNVERIFIED }
        if (verified.isEmpty()) return emptyList()
        val events = mutableListOf<VerificationEvent>()
        for (r in rescan) {
            val key = SpeciesKey.of(r.scientificName)
            var best: DetectionResult? = null
            var bestOverlap = 0f
            var bestStatus = VerificationStatus.UNVERIFIED
            var bestCorrected: String? = null
            for (o in verified) {
                val sameSpecies = SpeciesKey.of(o.scientificName) == key
                val correctedTo = o.verificationStatus == VerificationStatus.CORRECTED &&
                    o.correctedSpecies?.let { SpeciesKey.of(it) == key } == true
                if (!sameSpecies && !correctedTo) continue
                val ov = overlapSec(r, o)
                if (ov <= 0f) continue
                val better = best == null || ov > bestOverlap ||
                    (ov == bestOverlap && (o.verifiedAtMs ?: 0L) > (best.verifiedAtMs ?: 0L))
                if (!better) continue
                best = o
                bestOverlap = ov
                if (sameSpecies) {
                    bestStatus = o.verificationStatus
                    bestCorrected = if (o.verificationStatus == VerificationStatus.CORRECTED) o.correctedSpecies else null
                } else {
                    bestStatus = VerificationStatus.CONFIRMED
                    bestCorrected = null
                }
            }
            val src = best ?: continue
            events += VerificationEvent(
                detectionId = r.id,
                status = bestStatus,
                correctedSpecies = bestCorrected,
                verifiedAtMs = src.verifiedAtMs ?: nowMs,
                newValue = SOURCE_PREFIX + src.id
            )
        }
        return events
    }
}
