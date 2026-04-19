package ch.etasystems.pirol.ml

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf

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

    /** Anzahl aktuell gespeicherter Detektionen */
    val size: Int
        @Synchronized get() = detections.size
}
