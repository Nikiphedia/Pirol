package ch.etasystems.pirol.location

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * T191: Ein Punkt der Route, eine Zeile in `track.jsonl`.
 * [time] = ISO 8601 mit Offset (Lokalzeit, Zeitzone-Regel CLAUDE.md).
 * [detectionFix] = Fix stammt aus dem GPS-Modus "Nur bei Detektion" (Spur lueckenhaft).
 */
@Serializable
data class TrackPoint(
    val time: String,
    val lat: Double,
    val lon: Double,
    val accuracyM: Float? = null,
    val altitudeM: Double? = null,
    val detectionFix: Boolean = false
)

/** T191: Route einer Session (fuer die Karte). */
data class SessionTrack(val sessionId: String, val points: List<TrackPoint>) {
    /** Spur nur aus Detektions-Fixes (Modus "Nur bei Detektion") → auf der Karte gestrichelt */
    val sparse: Boolean get() = points.isNotEmpty() && points.all { it.detectionFix }
}

/** T191: Reine Logik der Routenaufzeichnung (ohne Android-Abhaengigkeit, JVM-testbar). */
object TrackLogic {
    /** Dateiname der Live-Spur im Session-Ordner */
    const val TRACK_FILE = "track.jsonl"

    /** Neuer Punkt erst ab dieser Bewegung zum letzten gespeicherten Punkt ... */
    const val MIN_DISTANCE_M = 10.0

    /** ... oder wenn seit dem letzten gespeicherten Punkt so viel Zeit vergangen ist */
    const val MAX_INTERVAL_MS = 60_000L

    /** Ungenauere Fixes (oder ohne Genauigkeitsangabe) werden verworfen */
    const val MAX_ACCURACY_M = 50f

    /** Fixes, die so lange vor dem Routenstart liegen, sind Reste einer frueheren Aufnahme */
    const val MAX_START_AGE_MS = 120_000L

    private const val EARTH_RADIUS_M = 6_371_000.0

    private val json = Json { encodeDefaults = false; explicitNulls = false }
    private val jsonLenient = Json { ignoreUnknownKeys = true }

    /** Grosskreis-Distanz (Haversine) in Metern */
    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(a)))
    }

    /**
     * Wird [candidate] als neuer Routenpunkt gespeichert?
     * - Genauigkeit fehlt oder > [MAX_ACCURACY_M] → nein
     * - aelter als [MAX_START_AGE_MS] vor [trackStartMs] → nein (alter Fix im StateFlow)
     * - erster Punkt → ja
     * - nicht juenger als der letzte Punkt → nein
     * - Bewegung >= [MIN_DISTANCE_M] oder Abstand >= [MAX_INTERVAL_MS] → ja
     */
    fun accept(last: LocationData?, candidate: LocationData, trackStartMs: Long): Boolean {
        val acc = candidate.accuracyM ?: return false
        if (acc.isNaN() || acc > MAX_ACCURACY_M) return false
        if (candidate.timestampMs < trackStartMs - MAX_START_AGE_MS) return false
        if (last == null) return true
        val dt = candidate.timestampMs - last.timestampMs
        if (dt <= 0L) return false
        if (dt >= MAX_INTERVAL_MS) return true
        return distanceM(last.latitude, last.longitude, candidate.latitude, candidate.longitude) >= MIN_DISTANCE_M
    }

    /** Ausduennen einer ganzen Fix-Folge (gleiche Regel wie live) */
    fun thin(fixes: List<LocationData>, trackStartMs: Long): List<LocationData> {
        val out = mutableListOf<LocationData>()
        for (f in fixes) if (accept(out.lastOrNull(), f, trackStartMs)) out += f
        return out
    }

    fun toPoint(fix: LocationData, zone: ZoneId, detectionFix: Boolean): TrackPoint = TrackPoint(
        time = OffsetDateTime.ofInstant(Instant.ofEpochMilli(fix.timestampMs), zone)
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        lat = fix.latitude,
        lon = fix.longitude,
        accuracyM = fix.accuracyM,
        altitudeM = fix.altitudeM?.takeIf { !it.isNaN() },
        detectionFix = detectionFix
    )

    /** JSONL-Zeile inkl. Zeilenende */
    fun encodeLine(point: TrackPoint): String = json.encodeToString(point) + "\n"

    /**
     * Liest `track.jsonl`. Unlesbare Zeilen (z.B. halb geschriebene letzte Zeile nach einem
     * Prozess-Kill) und Punkte ohne gueltige Koordinaten werden uebersprungen.
     */
    fun parseLines(text: String): List<TrackPoint> =
        text.lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                try { jsonLenient.decodeFromString<TrackPoint>(line.trim()) } catch (_: Exception) { null }
            }
            .filter { it.lat in -90.0..90.0 && it.lon in -180.0..180.0 }
            .toList()

    /** Epoch-Millis eines Punkts (null = Zeit unlesbar) */
    fun epochMs(point: TrackPoint): Long? =
        try { OffsetDateTime.parse(point.time).toInstant().toEpochMilli() } catch (_: Exception) { null }
}

/**
 * T191: Zeichnet waehrend einer Aufnahme die GPS-Spur auf.
 *
 * Quelle ist der Positions-Flow des [LocationProvider] — es wird KEIN eigenes GNSS eingeschaltet.
 * Im Modus "Nur bei Detektion" (T152) entsteht die Spur daher nur aus den Detektions-Fixes.
 * Jeder angenommene Punkt wird sofort als Zeile an `track.jsonl` angehaengt (crashsicher).
 *
 * @param locations Positions-Flow (Produktiv: LocationProvider.location)
 * @param appendLine Haengt eine fertige JSONL-Zeile an track.jsonl der aktiven Session an
 */
class TrackRecorder(
    private val locations: Flow<LocationData?>,
    private val appendLine: suspend (String) -> Unit,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val zone: () -> ZoneId = { ZoneId.systemDefault() }
) {
    companion object {
        private const val TAG = "TrackRecorder"
    }

    private var job: Job? = null

    private val _liveTrack = MutableStateFlow<SessionTrack?>(null)
    /** Spur der laufenden Aufnahme (null = keine Aufzeichnung) */
    val liveTrack: StateFlow<SessionTrack?> = _liveTrack.asStateFlow()

    /** Aufzeichnung fuer [sessionId] starten (idempotent: laufende Aufzeichnung wird ersetzt). */
    @Synchronized
    fun start(sessionId: String, detectionOnly: Boolean) {
        job?.cancel()
        val startMs = clock()
        _liveTrack.value = SessionTrack(sessionId, emptyList())
        job = scope.launch {
            var last: LocationData? = null
            locations.filterNotNull().collect { fix ->
                if (!TrackLogic.accept(last, fix, startMs)) return@collect
                last = fix
                val point = TrackLogic.toPoint(fix, zone(), detectionOnly)
                try {
                    // Zeile nicht durch stop() mitten im Schreiben abbrechen
                    withContext(NonCancellable) { appendLine(TrackLogic.encodeLine(point)) }
                } catch (e: Exception) {
                    Log.w(TAG, "track.jsonl: Punkt nicht geschrieben", e)
                }
                _liveTrack.value = _liveTrack.value?.let { it.copy(points = it.points + point) }
            }
        }
        Log.i(TAG, "Routenaufzeichnung gestartet ($sessionId, nurDetektion=$detectionOnly)")
    }

    /** Aufzeichnung beenden; kehrt erst zurueck, wenn alle Punkte geschrieben sind. */
    suspend fun stop() {
        val j = synchronized(this) { job.also { job = null } }
        j?.cancelAndJoin()
        val n = _liveTrack.value?.points?.size ?: 0
        _liveTrack.value = null
        if (j != null) Log.i(TAG, "Routenaufzeichnung beendet ($n Punkte)")
    }
}
