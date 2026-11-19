package ch.etasystems.pirol.location

/**
 * T143: Reine Entscheidungslogik fuer die Position einer neuen Detektion (ohne Android-Abhaengigkeit).
 */
object DetectionFixPolicy {
    /** Maximales Alter des letzten Fixes, bevor fuer eine Detektion ein frischer Fix angefordert wird. */
    const val MAX_FIX_AGE_MS = 15_000L

    /** Timeout fuer die Anfrage eines frischen Fixes. */
    const val FRESH_FIX_TIMEOUT_MS = 10_000L

    /** Herkunft des an eine Detektion gehaengten Fixes. */
    enum class Source { FRESH, LAST, NONE }

    /** Gewaehlter Fix + Herkunft; [stale] = der letzte (alte/ungenaue) Fix wurde mangels Alternative angehaengt. */
    data class Choice(val fix: LocationData?, val source: Source, val stale: Boolean)

    fun ageMs(fix: LocationData, nowMs: Long): Long = (nowMs - fix.timestampMs).coerceAtLeast(0L)

    /** Ist der Fix zu alt oder zu ungenau fuer eine Detektion? */
    fun isStale(fix: LocationData?, nowMs: Long, maxAccuracyM: Float, maxAgeMs: Long = MAX_FIX_AGE_MS): Boolean {
        if (fix == null) return true
        if (ageMs(fix, nowMs) > maxAgeMs) return true
        val acc = fix.accuracyM
        return acc == null || acc > maxAccuracyM
    }

    /** Frischer Fix noetig? Ja, wenn kein Fix vorhanden oder der letzte zu alt/ungenau ist. */
    fun needsFreshFix(last: LocationData?, nowMs: Long, maxAccuracyM: Float, maxAgeMs: Long = MAX_FIX_AGE_MS): Boolean =
        isStale(last, nowMs, maxAccuracyM, maxAgeMs)

    /**
     * Welcher Fix wird angehaengt? Der frische, wenn er genau genug ist oder besser als der letzte
     * (bzw. kein letzter existiert); sonst der letzte bekannte.
     */
    fun choose(
        last: LocationData?,
        fresh: LocationData?,
        nowMs: Long,
        maxAccuracyM: Float,
        maxAgeMs: Long = MAX_FIX_AGE_MS
    ): Choice {
        if (fresh != null) {
            val freshAcc = fresh.accuracyM ?: Float.MAX_VALUE
            val lastAcc = last?.accuracyM ?: Float.MAX_VALUE
            if (freshAcc <= maxAccuracyM || last == null || freshAcc <= lastAcc) {
                return Choice(fresh, Source.FRESH, stale = false)
            }
        }
        if (last != null) return Choice(last, Source.LAST, stale = isStale(last, nowMs, maxAccuracyM, maxAgeMs))
        return Choice(null, Source.NONE, stale = false)
    }
}
