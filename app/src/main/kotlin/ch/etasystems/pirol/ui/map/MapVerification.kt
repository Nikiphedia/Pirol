package ch.etasystems.pirol.ui.map

import ch.etasystems.pirol.ml.VerificationEvent
import ch.etasystems.pirol.ml.VerificationStatus

/**
 * T113: Reine Logik fuer die Verifikation auf der Karte (testbar ohne Android).
 * Gleiches JSON-Format wie Live-/Analyse-Tab: VerificationEvent(status, correctedSpecies, verifiedAtMs).
 */
object MapVerification {

    /** Vorheriger Zustand fuer Undo (Marker wird fuer das Wiedereinblenden mitgefuehrt). */
    data class UndoRecord(
        val marker: MapViewModel.MapMarker,
        val previousStatus: VerificationStatus,
        val previousCorrected: String?
    )

    /** Event fuer eine neue Verifikation (verifications.jsonl). */
    fun eventFor(detectionId: String, status: VerificationStatus, nowMs: Long) =
        VerificationEvent(detectionId = detectionId, status = status, verifiedAtMs = nowMs)

    /** Event, das den vorherigen Status wiederherstellt (historische Sessions: append statt entfernen). */
    fun undoEvent(record: UndoRecord, nowMs: Long) = VerificationEvent(
        detectionId = record.marker.detectionId,
        status = record.previousStatus,
        correctedSpecies = record.previousCorrected,
        verifiedAtMs = nowMs,
        actionType = VerificationEvent.ACTION_UNDO
    )

    /** Marker nach der Aktion in der Karte sichtbar? (T108-Filter) */
    fun visibleAfter(status: VerificationStatus): Boolean = isVisibleOnMap(status)

    /** Snackbar-Text (Deutsch). */
    fun message(status: VerificationStatus, name: String): String = when (status) {
        VerificationStatus.CONFIRMED -> "✓ $name bestätigt"
        VerificationStatus.REJECTED -> "✗ $name abgelehnt"
        VerificationStatus.UNCERTAIN -> "? $name als unsicher markiert"
        else -> name
    }

    /** Marker mit neuem Status (Anzeige-Zustand in Listen/Cluster). */
    fun withStatus(m: MapViewModel.MapMarker, status: VerificationStatus) = m.copy(verificationStatus = status)
}
