package ch.etasystems.pirol.location

/**
 * T206: Reine Entscheidungslogik, ob ausserhalb einer Aufnahme ein Standort-Fix angefordert wird
 * (Programmstart / Vordergrund, Karten-Zoom, Gemeinde-Knopf). Ohne Android-Abhaengigkeit.
 */
object StartFixPolicy {
    /** Ein Fix gilt bis zu diesem Alter als aktuell genug (Vordergrund-Wechsel). */
    const val MAX_AGE_MS = 2 * 60 * 1000L

    /** Timeout fuer den frischen Start-Fix. */
    const val TIMEOUT_MS = 30_000L

    /** Fix noetig? Ja, wenn keiner da ist oder er zu alt bzw. zu ungenau ist. */
    fun needsFix(last: LocationData?, nowMs: Long, maxAccuracyM: Float): Boolean =
        DetectionFixPolicy.isStale(last, nowMs, maxAccuracyM, MAX_AGE_MS)

    /** Soll der neue Fix den bestehenden ersetzen? Nur wenn genau genug und nicht aelter. */
    fun shouldPublish(current: LocationData?, fresh: LocationData, maxAccuracyM: Float): Boolean {
        val acc = fresh.accuracyM ?: return false
        if (acc > maxAccuracyM) return false
        return current == null || fresh.timestampMs >= current.timestampMs
    }
}
