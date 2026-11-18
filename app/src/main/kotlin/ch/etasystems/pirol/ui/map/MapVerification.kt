package ch.etasystems.pirol.ui.map

import ch.etasystems.pirol.ml.VerificationEvent
import ch.etasystems.pirol.ml.VerificationStatus
import ch.etasystems.pirol.R
import ch.etasystems.pirol.ui.AppStrings

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

    /** Snackbar-Text in der App-Sprache; [get] loest die String-Ressource auf (in Tests austauschbar). */
    fun message(
        status: VerificationStatus,
        name: String,
        get: (Int, String) -> String = { id, arg -> AppStrings.get(id, arg) }
    ): String = when (status) {
        VerificationStatus.CONFIRMED -> get(R.string.map_bestaetigt, name)
        VerificationStatus.REJECTED -> get(R.string.live_abgelehnt, name)
        VerificationStatus.UNCERTAIN -> get(R.string.live_als_unsicher_markiert, name)
        else -> name
    }

    /** Marker mit neuem Status (Anzeige-Zustand in Listen/Cluster). */
    fun withStatus(m: MapViewModel.MapMarker, status: VerificationStatus) = m.copy(verificationStatus = status)
}
