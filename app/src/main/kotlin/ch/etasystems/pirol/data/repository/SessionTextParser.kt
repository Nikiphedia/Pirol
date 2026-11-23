package ch.etasystems.pirol.data.repository

import ch.etasystems.pirol.data.export.RavenExporter
import ch.etasystems.pirol.ml.DetectionResult
import ch.etasystems.pirol.ml.VerificationEvent
import ch.etasystems.pirol.ui.analysis.DetectionOccurrences
import ch.etasystems.pirol.ui.map.MapPositionMove

/**
 * T251 (N7): Auswertung der Session-Texte (detections.jsonl, verifications.jsonl), aus SessionManager
 * herausgeloest. Rein, ohne IO; unlesbare Zeilen werden uebersprungen.
 */
internal object SessionTextParser {

    fun detections(text: String): List<DetectionResult> =
        text.lineSequence().filter { it.isNotBlank() }.mapNotNull { line ->
            try { SessionJson.lenient.decodeFromString<DetectionResult>(line) } catch (_: Exception) { null }
        }.toList()

    fun events(text: String): List<VerificationEvent> =
        text.lineSequence().filter { it.isNotBlank() }.mapNotNull { line ->
            try { SessionJson.lenient.decodeFromString<VerificationEvent>(line) } catch (_: Exception) { null }
        }.toList()

    /** Verifikations-Events auf die Detektionen anwenden (T165: POSITION_MOVED setzt nur die Position). */
    fun applyVerifications(detections: List<DetectionResult>, events: List<VerificationEvent>): List<DetectionResult> {
        val out = detections.toMutableList()
        val index = HashMap<String, Int>(out.size * 2)
        out.forEachIndexed { i, d -> index.putIfAbsent(d.id, i) }
        for (v in events) {
            val idx = index[v.detectionId] ?: continue
            val moved = MapPositionMove.parse(v)
            if (moved != null) {
                out[idx] = out[idx].copy(latitude = moved.first, longitude = moved.second)
                continue
            }
            if (v.actionType == VerificationEvent.ACTION_POSITION_MOVED) continue
            out[idx] = out[idx].copy(
                verificationStatus = v.status,
                correctedSpecies = v.correctedSpecies,
                verifiedAtMs = v.verifiedAtMs
            )
        }
        return out
    }

    /** T167: Wanduhr-Zeiten der absorbierten Fenster je Anker-Detektion (CLUSTER_MERGED). */
    fun mergeTimestamps(veriText: String): Map<String, List<DetectionOccurrences.MergeInfo>> {
        val map = mutableMapOf<String, MutableList<DetectionOccurrences.MergeInfo>>()
        for (event in events(veriText)) {
            if (event.actionType != VerificationEvent.ACTION_CLUSTER_MERGED) continue
            val info = DetectionOccurrences.parseMergeInfo(event.correctedSpecies) ?: continue
            map.getOrPut(event.detectionId) { mutableListOf() }.add(info)
        }
        return map
    }

    /** T85: DetectionId -> Simultanlinks (simultaneous / probable / sameIndividual). */
    fun simultaneousLinks(veriText: String): Map<String, RavenExporter.SimultaneousLinkSummary> {
        val map = mutableMapOf<String, RavenExporter.SimultaneousLinkSummary>()
        for (event in events(veriText)) {
            val otherId = event.newValue ?: continue
            val existing = map.getOrDefault(event.detectionId, RavenExporter.SimultaneousLinkSummary())
            map[event.detectionId] = when (event.actionType) {
                VerificationEvent.ACTION_SIMULTANEOUS_LINK -> existing.copy(simultaneous = existing.simultaneous + otherId)
                VerificationEvent.ACTION_PROBABLE_LINK -> existing.copy(probable = existing.probable + otherId)
                VerificationEvent.ACTION_SAME_INDIVIDUAL -> existing.copy(sameIndividual = existing.sameIndividual + otherId)
                else -> existing
            }
        }
        return map
    }
}
