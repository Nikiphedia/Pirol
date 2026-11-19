package ch.etasystems.pirol.location

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * T152: Reine Regel fuer den GPS-Modus "Nur bei Detektion" (ohne Android-Abhaengigkeit, Uhr injizierbar).
 * - Ist der letzte Fix juenger als [REUSE_MAX_AGE_MS], wird er wiederverwendet.
 * - Zwischen zwei Fix-Anfragen liegen mindestens [MIN_FIX_GAP_MS] (auch bei Fehlschlag).
 * - Gleichzeitige Detektionen loesen nur EINEN Fix aus (Mutex; Nachzuegler sehen den frischen Fix).
 */
class DetectionFixGate(private val clock: () -> Long = { System.currentTimeMillis() }) {
    companion object {
        const val MIN_FIX_GAP_MS = 30_000L
        const val REUSE_MAX_AGE_MS = 30_000L
    }

    private val mutex = Mutex()
    private var latest: LocationData? = null
    private var lastRequestAtMs: Long? = null

    @Volatile var freshCount = 0; private set
    @Volatile var reusedCount = 0; private set

    /** [choice] = anzuhaengender Fix; [fetched] = in diesem Aufruf wurde ein neuer Fix angefordert. */
    data class Result(val choice: DetectionFixPolicy.Choice, val fetched: Boolean)

    /** Letzter bekannter Fix der Regel (null = noch keiner). */
    fun latestFix(): LocationData? = latest

    /** Startfix einsetzen (zaehlt als Anfrage; ein fehlgeschlagener Startfix verbraucht den Abstand nicht). */
    suspend fun seed(fix: LocationData) = mutex.withLock {
        latest = fix
        lastRequestAtMs = clock()
        freshCount++
    }

    suspend fun obtain(last: LocationData?, maxAccuracyM: Float, fetch: suspend () -> LocationData?): Result =
        mutex.withLock {
            val now = clock()
            val known = listOfNotNull(last, latest).maxByOrNull { it.timestampMs }
            if (known != null && DetectionFixPolicy.ageMs(known, now) <= REUSE_MAX_AGE_MS) {
                reusedCount++
                return@withLock Result(DetectionFixPolicy.Choice(known, DetectionFixPolicy.Source.LAST, false), false)
            }
            val lastReq = lastRequestAtMs
            if (lastReq != null && now - lastReq < MIN_FIX_GAP_MS) {
                reusedCount++
                return@withLock Result(DetectionFixPolicy.choose(known, null, now, maxAccuracyM), false)
            }
            lastRequestAtMs = now
            val fresh = try { fetch() } catch (e: Exception) { null }
            val choice = DetectionFixPolicy.choose(known, fresh, clock(), maxAccuracyM)
            if (fresh != null) {
                freshCount++
                latest = fresh
            }
            Result(choice, fresh != null)
        }
}
