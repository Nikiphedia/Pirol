package ch.etasystems.pirol.ml

/**
 * T157: Merkt sich die letzte Bestaetigung ueber den Alarm (Benachrichtigung, AlarmActivity,
 * In-App-Hinweis), damit der Live-Tab sie kurz danach mit Snackbar "Rueckgaengig" anbieten kann.
 * Eintrag gilt [WINDOW_MS] und wird beim Abholen verbraucht. Uhr injizierbar (Tests).
 */
class PendingAlarmUndo(private val clock: () -> Long = System::currentTimeMillis) {
    data class Entry(
        val detectionId: String,
        val commonName: String,
        val previousStatus: VerificationStatus,
        val previousCorrected: String?,
        /** true wenn ein Eintrag in verifications.jsonl geschrieben wurde (aktive Session) */
        val written: Boolean
    )

    companion object {
        const val WINDOW_MS = 30_000L
    }

    private val lock = Any()
    private var entry: Entry? = null
    private var recordedAtMs = 0L

    /** Wird aufgerufen, sobald ein neuer Eintrag vorliegt (z.B. Live-Tab sichtbar). */
    @Volatile var onRecorded: (() -> Unit)? = null

    fun record(e: Entry) {
        synchronized(lock) { entry = e; recordedAtMs = clock() }
        onRecorded?.invoke()
    }

    fun clear() = synchronized(lock) { entry = null }

    /** Liefert den Eintrag falls noch im Zeitfenster und verbraucht ihn. */
    fun take(): Entry? = synchronized(lock) {
        val e = entry ?: return null
        entry = null
        if (clock() - recordedAtMs > WINDOW_MS) null else e
    }
}
