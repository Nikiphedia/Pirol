package ch.etasystems.pirol.ml

import android.util.Log
import ch.etasystems.pirol.data.repository.SessionManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * T137: Verifikation ohne ViewModel. Gemeinsamer Pfad fuer Live-Tab (Mutex + JSONL-Schreiben) und
 * den Alarm ("Bestaetigen" in Benachrichtigung / AlarmActivity), damit Format und Serialisierung
 * (verifications.jsonl, Status-Ereignis ohne actionType, T117) nur an einer Stelle liegen.
 * Koin-Singleton, kein Scope noetig (suspend-Funktionen).
 */
class VerificationService(
    private val detectionListState: DetectionListState,
    private val sessionManager: SessionManager,
    clock: () -> Long = System::currentTimeMillis
) {
    companion object {
        private const val TAG = "VerificationService"
    }

    /** T157: letzte Alarm-Bestaetigung, die in der App noch rueckgaengig gemacht werden kann. */
    val alarmUndo = PendingAlarmUndo(clock)

    /** Serialisiert Verifikations-IO (Schnell-Taps, Alarm + UI gleichzeitig). */
    val mutex = Mutex()

    /** Status-Ereignis fuer verifications.jsonl (einzige Stelle fuer das Standardformat). */
    fun statusEvent(
        detectionId: String,
        status: VerificationStatus,
        correctedSpecies: String? = null
    ) = VerificationEvent(
        detectionId = detectionId,
        status = status,
        correctedSpecies = correctedSpecies,
        verifiedAtMs = System.currentTimeMillis()
    )

    /**
     * Detektion bestaetigen: Zustand in [DetectionListState] setzen und (bei aktiver Session) das
     * Ereignis schreiben. Bei Schreibfehler wird der vorherige Zustand wiederhergestellt.
     * @return true wenn die Detektion gefunden und bestaetigt wurde
     */
    suspend fun confirm(detectionId: String): Boolean {
        val detection = detectionListState.getDetections().find { it.id == detectionId } ?: return false
        val prevStatus = detection.verificationStatus
        val prevCorrected = detection.correctedSpecies
        detectionListState.updateVerification(detectionId, VerificationStatus.CONFIRMED, null)
        alarmUndo.record(PendingAlarmUndo.Entry(detectionId, detection.commonName, prevStatus, prevCorrected, written = sessionManager.isActive))
        if (!sessionManager.isActive) return true
        mutex.withLock {
            try {
                sessionManager.appendVerification(statusEvent(detectionId, VerificationStatus.CONFIRMED))
            } catch (e: Exception) {
                Log.e(TAG, "Verifikation schreiben fehlgeschlagen", e)
                detectionListState.updateVerification(detectionId, prevStatus, prevCorrected)
                alarmUndo.clear()
                return false
            }
        }
        return true
    }

    /** T157: Alarm-Bestaetigung zuruecknehmen (Status + UNDO ueber denselben Pfad wie im Live-Tab). */
    suspend fun undoAlarmConfirm(entry: PendingAlarmUndo.Entry) {
        detectionListState.updateVerification(entry.detectionId, entry.previousStatus, entry.previousCorrected)
        if (!entry.written) return
        mutex.withLock {
            try {
                sessionManager.removeLastVerification(entry.detectionId)
            } catch (e: Exception) {
                Log.e(TAG, "Undo schreiben fehlgeschlagen", e)
            }
        }
    }
}
