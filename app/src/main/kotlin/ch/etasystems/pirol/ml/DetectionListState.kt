package ch.etasystems.pirol.ml

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Ergebnis eines addOrCluster()-Aufrufs.
 *
 * New: Detektion war neu (keine Ueberschneidung gefunden), wurde als neue Karte eingefuegt.
 * Merged: Detektion wurde in bestehende Karte absorbiert; anchor ist die aktualisierte Anker-Detection.
 */
sealed class ClusterResult {
    data class New(val detection: DetectionResult) : ClusterResult()
    data class Merged(val anchor: DetectionResult, val absorbedId: String) : ClusterResult()
}

/**
 * Haversine-Distanz zwischen zwei GPS-Punkten in Metern.
 */
private fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val R = 6371000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
    return 2 * R * asin(sqrt(a))
}

/**
 * Thread-safe State-Holder fuer Detektions-Ergebnisse.
 *
 * Analog zu SpectrogramState: wird vom InferenceWorker (Coroutine-Scope) befuellt
 * und von Compose-UI (Recomposition) gelesen. Zugriffe sind ueber @Synchronized abgesichert.
 *
 * Deduplizierungs-Logik (T27): Jede Art erscheint nur EINMAL in der Liste.
 * Bei Wiedererkennung wird die hoechste Confidence behalten und der Zaehler (detectionCount)
 * inkrementiert. Art bleibt an ihrer bestehenden Position.
 *
 * @param maxDetections Maximale Anzahl gespeicherter Detektionen (FIFO)
 */
class DetectionListState(
    private val maxDetections: Int = 100
) {
    // Interne Liste: neueste zuerst
    private val detections = mutableListOf<DetectionResult>()

    // Compose-Recomposition-Trigger
    private val _version = mutableIntStateOf(0)
    val version: State<Int> = _version

    /**
     * Fuegt neue Detektionen hinzu (mit Deduplizierung pro Art).
     *
     * Fuer jede Detektion:
     * - Wenn gleiche Art (scientificName) bereits in der Liste:
     *   → Confidence max behalten, detectionCount erhoehen, Timestamp aktualisieren
     * - Sonst: Neue Detektion vorne einfuegen
     *
     * Aelteste Detektionen werden entfernt wenn maxDetections ueberschritten.
     */
    @Synchronized
    fun addDetections(newDetections: List<DetectionResult>) {
        if (newDetections.isEmpty()) return

        for (detection in newDetections) {
            val existingIndex = detections.indexOfFirst {
                it.scientificName == detection.scientificName
            }

            if (existingIndex >= 0) {
                // Art bereits vorhanden → aktualisieren
                val existing = detections[existingIndex]
                val now = System.currentTimeMillis()
                val timeSinceLastDetection = now - existing.lastDetectedMs

                val updatedDetection = if (detection.confidence > existing.confidence) {
                    // Hoeherer Confidence → Detektion ersetzen, verificationStatus beibehalten (T44)
                    detection.copy(
                        id = existing.id,
                        detectionCount = existing.detectionCount + 1,
                        lastDetectedMs = now,
                        verificationStatus = existing.verificationStatus,
                        correctedSpecies = existing.correctedSpecies,
                        verifiedAtMs = existing.verifiedAtMs
                    )
                } else {
                    // Niedrigerer Confidence → nur Count erhoehen + lastDetectedMs aktualisieren
                    existing.copy(
                        detectionCount = existing.detectionCount + 1,
                        timestampMs = detection.timestampMs,
                        lastDetectedMs = now
                    )
                }

                if (timeSinceLastDetection >= 10_000L) {
                    // Art zuletzt vor >= 10s gesehen → an Index 0 verschieben (T44)
                    detections.removeAt(existingIndex)
                    detections.add(0, updatedDetection)
                } else {
                    // Art zuletzt vor < 10s gesehen → Position beibehalten
                    detections[existingIndex] = updatedDetection
                }
            } else {
                // Neue Art → vorne einfuegen (T33: lastDetectedMs setzen)
                detections.add(0, detection.copy(lastDetectedMs = System.currentTimeMillis()))
                // Max-Limit einhalten
                while (detections.size > maxDetections) {
                    detections.removeAt(detections.lastIndex)
                }
            }
        }

        // Compose-Recomposition triggern
        _version.intValue++
    }

    /** Alle Detektionen (neueste zuerst) */
    @Synchronized
    fun getDetections(): List<DetectionResult> {
        return detections.toList()
    }

    /** Top-N nach Confidence (fuer kompakte Anzeige, z.B. Overlay) */
    @Synchronized
    fun getTopDetections(n: Int = 10): List<DetectionResult> {
        return detections
            .sortedByDescending { it.confidence }
            .take(n)
    }

    /** Alle Detektionen loeschen (z.B. bei Aufnahme-Neustart) */
    @Synchronized
    fun clear() {
        detections.clear()
        _version.intValue++
    }

    /**
     * Aktualisiert den Verifikations-Status einer Detektion.
     *
     * @param detectionId UUID der Detektion
     * @param status Neuer Status (CONFIRMED, REJECTED, CORRECTED)
     * @param correctedSpecies Nur bei CORRECTED: Korrigierter Artname
     * @return true wenn Detektion gefunden und aktualisiert
     */
    @Synchronized
    fun updateVerification(
        detectionId: String,
        status: VerificationStatus,
        correctedSpecies: String? = null
    ): Boolean {
        val index = detections.indexOfFirst { it.id == detectionId }
        if (index < 0) return false

        detections[index] = detections[index].copy(
            verificationStatus = status,
            correctedSpecies = if (status == VerificationStatus.CORRECTED) correctedSpecies else null,
            verifiedAtMs = System.currentTimeMillis()
        )
        _version.intValue++
        return true
    }

    /**
     * Ersetzt eine Detektion durch eine aktualisierte Version (T68: count/atlasCode/note).
     * Behaelt die Position in der Liste bei. Triggert Recomposition.
     *
     * @param updated Aktualisierte Detektion (gleiche id)
     * @return true wenn Detektion gefunden und ersetzt
     */
    @Synchronized
    fun updateDetection(updated: DetectionResult): Boolean {
        val index = detections.indexOfFirst { it.id == updated.id }
        if (index < 0) return false
        detections[index] = updated
        _version.intValue++
        return true
    }

    /**
     * Ersetzt eine Detektion durch eine vom Nutzer gewaehlte Alternative.
     *
     * - Original-Detektion wird als REPLACED markiert (bleibt an ihrer Position, ausgegraut)
     * - Alternative wird als neue Detektion an Index 0 eingefuegt (5s-Highlight)
     * - Falls die Alternative bereits in der Liste war, wird sie vorher entfernt (kein Duplikat)
     *
     * @param originalDetectionId UUID der zu ersetzenden Detektion
     * @param candidate Vom Nutzer gewaehlte Alternative
     * @return true wenn erfolgreich, false wenn originalDetectionId nicht gefunden
     */
    @Synchronized
    fun selectAlternative(
        originalDetectionId: String,
        candidate: DetectionCandidate
    ): Boolean {
        val originalIndex = detections.indexOfFirst { it.id == originalDetectionId }
        if (originalIndex < 0) return false

        val original = detections[originalIndex]
        val now = System.currentTimeMillis()

        // 1. Original als REPLACED markieren (bleibt an Position)
        detections[originalIndex] = original.copy(
            verificationStatus = VerificationStatus.REPLACED,
            correctedSpecies = candidate.scientificName,
            verifiedAtMs = now
        )

        // 2. Falls die Alternative-Art schon woanders in der Liste ist → entfernen
        detections.removeAll {
            it.id != originalDetectionId &&
            it.scientificName == candidate.scientificName
        }

        // 3. Alternative als neue Detektion an Index 0
        val alternativeDetection = DetectionResult(
            id = java.util.UUID.randomUUID().toString(),
            scientificName = candidate.scientificName,
            commonName = candidate.commonName,
            confidence = candidate.confidence,
            timestampMs = now,
            chunkStartSec = original.chunkStartSec,
            chunkEndSec = original.chunkEndSec,
            sampleRate = original.sampleRate,
            latitude = original.latitude,
            longitude = original.longitude,
            verificationStatus = VerificationStatus.UNVERIFIED,
            candidates = emptyList(),
            detectionCount = 1,
            lastDetectedMs = now
        )
        detections.add(0, alternativeDetection)

        // 4. Max-Limit einhalten
        while (detections.size > maxDetections) {
            detections.removeAt(detections.lastIndex)
        }

        _version.intValue++
        return true
    }

    /**
     * Cluster-Algorithmus (T70): Fuegt eine Detektion ein oder absorbiert sie in eine bestehende.
     *
     * Wenn clusterEnabled=false: klassisches T44-Verhalten (addDetections mit einer Detektion).
     * Wenn clusterEnabled=true: Suche nach Art + Zeitfenster + Radius-Match.
     *   - Match: Anker aktualisieren (lastSeenAtMs, detectionCount+1, confidence=max) → Merged
     *   - Kein Match: Neue Detektion einfuegen → New
     *
     * radiusM=0 → kein Radius-Check (nur Zeit).
     * timeWindowMin=0 → windowMs=0 → nie Zeit-Match → jede Detektion ist neu.
     *
     * GPS fehlt auf einer Seite → Zeit-Match reicht (kein Radius-Check).
     *
     * @return ClusterResult.New oder ClusterResult.Merged (fuer Persistenz in LiveViewModel)
     */
    @Synchronized
    fun addOrCluster(
        detection: DetectionResult,
        clusterEnabled: Boolean,
        radiusM: Int,
        timeWindowMin: Int
    ): ClusterResult {
        if (!clusterEnabled) {
            // Toggle aus → klassisches T44-Verhalten
            addDetections(listOf(detection))
            return ClusterResult.New(detection)
        }

        val nowMs = System.currentTimeMillis()
        val windowMs = timeWindowMin * 60_000L

        val matchIndex = detections.indexOfFirst { existing ->
            existing.scientificName == detection.scientificName &&
            (nowMs - existing.lastSeenAtMs) <= windowMs &&
            run {
                val lat1 = existing.latitude
                val lon1 = existing.longitude
                val lat2 = detection.latitude
                val lon2 = detection.longitude
                if (lat1 != null && lon1 != null && lat2 != null && lon2 != null && radiusM > 0) {
                    distanceM(lat1, lon1, lat2, lon2) <= radiusM
                } else {
                    // GPS fehlt auf einer Seite (oder radiusM=0) → nur Zeit-Match genuegt
                    true
                }
            }
        }

        return if (matchIndex >= 0) {
            // Cluster-Merge: Anker aktualisieren
            val existing = detections[matchIndex]
            val updated = existing.copy(
                lastSeenAtMs = detection.timestampMs,
                detectionCount = existing.detectionCount + 1,
                confidence = maxOf(existing.confidence, detection.confidence)
            )
            detections[matchIndex] = updated
            _version.intValue++
            ClusterResult.Merged(anchor = updated, absorbedId = detection.id)
        } else {
            // Neue Detection: firstSeenAtMs = lastSeenAtMs = timestampMs (Defaults in DetectionResult)
            detections.add(0, detection.copy(lastDetectedMs = nowMs))
            while (detections.size > maxDetections) detections.removeAt(detections.lastIndex)
            _version.intValue++
            ClusterResult.New(detection)
        }
    }

    /**
     * Fuegt eine manuelle Detection direkt ein — OHNE Cluster-Logik und OHNE Dedup.
     * User-Entscheidung ueberschreibt automatische Heuristik (T72).
     */
    @Synchronized
    fun addManualDetection(detection: DetectionResult) {
        detections.add(0, detection)
        while (detections.size > maxDetections) detections.removeAt(detections.lastIndex)
        _version.intValue++
    }

    /** Anzahl aktuell gespeicherter Detektionen */
    val size: Int
        @Synchronized get() = detections.size
}
