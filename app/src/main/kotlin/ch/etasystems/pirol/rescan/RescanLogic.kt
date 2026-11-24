package ch.etasystems.pirol.rescan

import ch.etasystems.pirol.data.repository.RecordingSegment
import ch.etasystems.pirol.ml.DetectionResult
import ch.etasystems.pirol.util.parseInstantCompat
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
 * - `rescan-<id>.json` — Einstellungen und Status (T260: zu Beginn mit RUNNING geschrieben, damit die
 *   Analyse die Variante schon waehrend des Scans zeigt; am Ende DONE oder ABORTED)
 * - `detections.updates.rescan-<id>.jsonl` — T260: Updates waehrend des Scans (append-only wie live,
 *   [ch.etasystems.pirol.data.repository.DetectionUpdateLog]); am Ende in die Detektionsdatei uebernommen
 */
object RescanFiles {
    const val DETECTIONS = "detections.jsonl"
    const val VERIFICATIONS = "verifications.jsonl"
    const val RAVEN = "recording.selections.txt"
    /** Zusatzdatei der Detektions-Updates des Originals (T251) */
    const val UPDATES = "detections.updates.jsonl"

    private const val ID_PATTERN = """\d{8}-\d{6}(?:-\d+)?"""
    private val META_REGEX = Regex("""rescan-($ID_PATTERN)\.json""")
    private val DETECTIONS_REGEX = Regex("""detections\.rescan-($ID_PATTERN)\.jsonl""")
    private val ID_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    fun detections(id: String?): String = if (id == null) DETECTIONS else "detections.rescan-$id.jsonl"
    fun verifications(id: String?): String = if (id == null) VERIFICATIONS else "verifications.rescan-$id.jsonl"
    fun raven(id: String?): String = if (id == null) RAVEN else "recording.rescan-$id.selections.txt"
    fun meta(id: String): String = "rescan-$id.json"
    /** T260: Zusatzdatei der Detektions-Updates (Original: [UPDATES]). Bewusst nicht `detections.rescan-...`. */
    fun updates(id: String?): String = if (id == null) UPDATES else "detections.updates.rescan-$id.jsonl"

    /** T260: Zusatzdatei zu einer Detektionsdatei (Original oder Variante); null = keine Detektionsdatei. */
    fun updatesFor(detectionsName: String): String? {
        if (detectionsName == DETECTIONS) return UPDATES
        val m = DETECTIONS_REGEX.matchEntire(detectionsName) ?: return null
        return updates(m.groupValues[1])
    }

    /** Alle Dateien einer Variante; Metadaten zuerst (beim Loeschen verschwindet die Variante sofort). */
    fun filesOf(id: String): List<String> = listOf(meta(id), detections(id), updates(id), verifications(id), raven(id))

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

/**
 * T260: Status einer Variante in `rescan-<id>.json` (reine Logik).
 * RUNNING = Scan laeuft (Variante schon sichtbar und verifizierbar), DONE = fertig, ABORTED = abgebrochen
 * (Knopf, Aufnahme gestartet, Fehler) oder nach Prozess-Kill wiederhergestellt; Teilergebnis bleibt.
 */
object RescanStatus {
    const val RUNNING = "RUNNING"
    const val DONE = "DONE"
    const val ABORTED = "ABORTED"

    /**
     * Angezeigter Status: RUNNING ohne laufenden Rescan dieser Variante ([runningId] = Kennung des Rescans,
     * der in diesem Prozess laeuft, sonst null) war ein Prozess-Kill -> ABORTED.
     */
    fun effective(info: RescanInfo, runningId: String?): String =
        if (info.status == RUNNING && info.id != runningId) ABORTED else info.status

    /** Liegengebliebene Varianten (RUNNING, aber nicht in diesem Prozess aktiv), die abzuschliessen sind. */
    fun stale(list: List<RescanInfo>, runningId: String?): List<RescanInfo> =
        list.filter { it.status == RUNNING && it.id != runningId }

    /** Abgeschlossene Metadaten: Status, gescannte Sekunden und Zaehler der Endfassung. */
    fun finished(info: RescanInfo, status: String, scannedSec: Float?, detections: Int, transferred: Int): RescanInfo =
        info.copy(status = status, scannedSec = scannedSec ?: info.scannedSec, detectionCount = detections,
            transferredVerifications = transferred)

    /** Ganze Minuten fuer "abgebrochen bei x min" (abgerundet, nie negativ). */
    fun minutes(scannedSec: Float?): Int = ((scannedSec ?: 0f) / 60f).toInt().coerceAtLeast(0)
}

/**
 * T260: Update des Rescans (Cluster-Merge, Zweitmodell) auf den AKTUELLEN Stand einer Detektion anwenden,
 * die der Nutzer waehrend des Scans schon bearbeitet haben kann (reine Logik).
 *
 * Regel: Der Rescan setzt nur seine eigenen Felder (detectionCount, lastSeenAtMs/lastDetectedMs, Konfidenz und bestes
 * Fenster des Clusters, secondary*). Nutzerfelder bleiben immer: Status, Korrektur, verifiedAtMs, Stueckzahl,
 * Atlascode, Bemerkung, Memo, Sperre, Position, Art samt Kandidaten. Hat der Nutzer eine Alternative
 * gewaehlt (Art anders als im Rescan), gelten Konfidenz, bestes Fenster und Zweitmodell der alten Art nicht.
 */
object RescanLiveMerge {
    fun applyRunnerUpdate(current: DetectionResult, runner: DetectionResult): DetectionResult {
        val clustered = current.copy(
            detectionCount = maxOf(current.detectionCount, runner.detectionCount),
            lastSeenAtMs = maxOf(current.lastSeenAtMs, runner.lastSeenAtMs),
            lastDetectedMs = maxOf(current.lastDetectedMs, runner.lastDetectedMs)
        )
        if (SpeciesKey.of(current.scientificName) != SpeciesKey.of(runner.scientificName)) return clustered
        return clustered.copy(
            confidence = runner.confidence,
            bestStartSec = runner.bestStartSec,
            bestEndSec = runner.bestEndSec,
            secondaryModelId = runner.secondaryModelId,
            secondaryStatus = runner.secondaryStatus,
            secondaryConfidence = runner.secondaryConfidence,
            secondaryTopSpecies = runner.secondaryTopSpecies,
            secondaryTopConfidence = runner.secondaryTopConfidence,
            secondaryRank = runner.secondaryRank
        )
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

    /**
     * T255: Wanduhr (Epoch ms) eines Frames der Session-Achse. Importierte Sessions aus mehreren Dateien haben
     * Luecken zwischen den Segmenten; die Achse ist aber luecken-los. Tragen alle Segmente ihre Wanduhr
     * ([RecordingSegment.startedAt]), gilt Segment-Wanduhr + Offset im Segment; sonst [epoch0] + Offset.
     */
    fun wallClockAt(segments: List<RecordingSegment>?, sampleRate: Int, epoch0: Long): (Long) -> Long {
        val linear = { f: Long -> epoch0 + f * 1000L / sampleRate.coerceAtLeast(1) }
        if (segments.isNullOrEmpty() || sampleRate <= 0) return linear
        val anchors = segments.map { s ->
            val wall = s.startedAt?.let { parseInstantCompat(it) }?.toEpochMilli() ?: return linear
            Math.round(s.startSec.toDouble() * sampleRate) to wall
        }.sortedBy { it.first }
        return { f ->
            val a = anchors.lastOrNull { it.first <= f } ?: anchors.first()
            a.second + (f - a.first) * 1000L / sampleRate
        }
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

    /**
     * T260: Uebernahme am Ende (oder nach Kill beim naechsten Oeffnen) nur fuer Detektionen, die der Nutzer
     * waehrend des Scans nicht angefasst hat ([rescan] mit angewendeten Verifikationen der Variante:
     * UNVERIFIED und ohne verifiedAtMs). Nutzer-Verifikationen werden so nie ueberschrieben, und ein
     * wiederholter Aufruf (Kill zwischen Uebernahme und Metadaten) haengt nichts doppelt an.
     */
    fun transferUntouched(rescan: List<DetectionResult>, originals: List<DetectionResult>, nowMs: Long): List<VerificationEvent> =
        transfer(rescan.filter { it.verificationStatus == VerificationStatus.UNVERIFIED && it.verifiedAtMs == null }, originals, nowMs)

    /** T260: Zahl der Uebernahme-Events (`rescan_from:`) in einer Verifikationsdatei der Variante. */
    fun countTransferred(verificationsText: String?): Int =
        verificationsText?.lineSequence()?.count { it.contains("\"newValue\":\"$SOURCE_PREFIX") } ?: 0

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
