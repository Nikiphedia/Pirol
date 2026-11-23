package ch.etasystems.pirol.ml

/**
 * T237: "Alternative waehlen" ersetzt die Art der bestehenden Detektion (gleiche ID, Zeit, Audio, Fenster).
 * Es entsteht keine neue Detektion, andere Detektionen bleiben unberuehrt. Reine Logik, ohne IO.
 */
object AlternativeSelection {

    /**
     * Detektion mit der gewaehlten Alternative als Art. Die bisherige Art rueckt in die Kandidaten
     * (Wechsel zurueck moeglich). Status = CONFIRMED (bewusste Wahl), Zweitmodell-Ergebnis entfaellt
     * (galt der alten Art). Zeitstempel, Fenster, Position und Notizen bleiben.
     */
    fun apply(original: DetectionResult, candidate: DetectionCandidate, nowMs: Long): DetectionResult {
        val chosenKey = SpeciesKey.of(candidate.scientificName)
        val others = original.candidates.filter { SpeciesKey.of(it.scientificName) != chosenKey }
        val previous = DetectionCandidate(original.scientificName, original.commonName, original.confidence)
        return original.copy(
            scientificName = candidate.scientificName,
            commonName = candidate.commonName,
            confidence = candidate.confidence,
            candidates = (others + previous).sortedByDescending { it.confidence },
            verificationStatus = VerificationStatus.CONFIRMED,
            correctedSpecies = null,
            verifiedAtMs = nowMs,
            secondaryModelId = null,
            secondaryStatus = null,
            secondaryConfidence = null,
            secondaryTopSpecies = null,
            secondaryTopConfidence = null,
            secondaryRank = null
        )
    }

    /**
     * Undo/Rollback: nur die von [apply] geaenderten Felder aus [before] in [current] zurueckschreiben.
     * Spaetere Aenderungen (Cluster-Merge, Zaehler, Notiz) bleiben erhalten.
     */
    fun revert(current: DetectionResult, before: DetectionResult): DetectionResult = current.copy(
        scientificName = before.scientificName,
        commonName = before.commonName,
        confidence = before.confidence,
        candidates = before.candidates,
        verificationStatus = before.verificationStatus,
        correctedSpecies = before.correctedSpecies,
        verifiedAtMs = before.verifiedAtMs,
        secondaryModelId = before.secondaryModelId,
        secondaryStatus = before.secondaryStatus,
        secondaryConfidence = before.secondaryConfidence,
        secondaryTopSpecies = before.secondaryTopSpecies,
        secondaryTopConfidence = before.secondaryTopConfidence,
        secondaryRank = before.secondaryRank
    )

    /**
     * T251 (Zusatz T249): Undo der Alternative. Wie [revert], aber wurde seit [applied] neu verifiziert
     * (z.B. "Unsicher"), bleibt dieser Status: Das Undo entfernt in verifications.jsonl nur das
     * ALTERNATIVE_CONFIRMED (T249, M6), die spaetere Verifikation gilt dort weiter.
     */
    fun undo(current: DetectionResult, before: DetectionResult, applied: DetectionResult): DetectionResult {
        val reverted = revert(current, before)
        val verifiedSince = current.verificationStatus != applied.verificationStatus ||
            current.verifiedAtMs != applied.verifiedAtMs || current.correctedSpecies != applied.correctedSpecies
        return if (!verifiedSince) reverted else reverted.copy(
            verificationStatus = current.verificationStatus,
            correctedSpecies = current.correctedSpecies,
            verifiedAtMs = current.verifiedAtMs
        )
    }
}
