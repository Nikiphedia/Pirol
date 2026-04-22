package ch.etasystems.pirol.location

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import android.util.Log
import ch.etasystems.pirol.data.AppPreferences
import ch.etasystems.pirol.data.repository.GpsStats
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * GPS-Position als immutable Data-Objekt.
 */
data class LocationData(
    val latitude: Double,
    val longitude: Double,
    val altitudeM: Double?,   // Meter ueber Meeresspiegel (kann null sein)
    val accuracyM: Float?,    // Genauigkeit in Metern
    val timestampMs: Long     // Unix Timestamp
)

/**
 * Berechnet den Median einer Liste von Double-Werten.
 * Bei gerader Anzahl: Durchschnitt der beiden mittleren Werte.
 * Package-intern fuer Unit-Tests zugaenglich (T53).
 */
internal fun medianOf(values: List<Double>): Double {
    val sorted = values.sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2.0 else sorted[mid]
}

/**
 * GPS-Location-Provider basierend auf FusedLocationProviderClient.
 * Stellt aktuelle Position als StateFlow bereit.
 *
 * T53-Erweiterungen:
 * - Accuracy-Filter: Fixes schlechter als gpsMaxAccuracyMeters werden verworfen.
 * - LastKnown-Fallback: Bei Session-Start sofort letzten bekannten Fix publizieren
 *   (wenn <= 2 Min alt und accuracy ok), damit erste Detektion nicht null ist.
 * - Median-Smoothing: Rolling-Window 5 Fixes, Median auf lat/lon separat.
 * - Konfigurierbares GPS-Intervall aus AppPreferences.
 * - PRIORITY_HIGH_ACCURACY nur waehrend aktiver Session (AC4: keine Updates nach Stop).
 *
 * Lifecycle: Singleton via Koin. start()/stop() werden vom LiveViewModel gesteuert.
 */
class LocationProvider(
    private val context: Context,
    private val appPreferences: AppPreferences
) {

    companion object {
        private const val TAG = "LocationProvider"
        private const val LASTKNOWN_MAX_AGE_MS = 2 * 60 * 1000L  // 2 Minuten
        private const val SMOOTHING_WINDOW_SIZE = 5
    }

    // Aktuelle Position als StateFlow (null = noch keine Position)
    private val _location = MutableStateFlow<LocationData?>(null)
    val location: StateFlow<LocationData?> = _location.asStateFlow()

    private val fusedClient = LocationServices.getFusedLocationProviderClient(context)
    private var isTracking = false

    // --- Accuracy-Filter & Statistik (T53) ---
    private var fixCount = 0
    private var rejectedCount = 0
    private val acceptedAccuracies = mutableListOf<Float>()

    // --- Median-Smoothing (T53) ---
    private val fixWindow = ArrayDeque<LocationData>()  // max SMOOTHING_WINDOW_SIZE akzeptierte Fixes

    // LocationCallback fuer Updates
    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { loc ->
                val accuracy = if (loc.hasAccuracy()) loc.accuracy else Float.MAX_VALUE
                val maxAccuracy = appPreferences.gpsMaxAccuracyMeters

                // Accuracy-Filter: Fix verwerfen wenn zu ungenau
                if (accuracy > maxAccuracy) {
                    rejectedCount++
                    Log.d(TAG, "Fix verworfen: ±${accuracy}m > ${maxAccuracy}m (gesamt verworfen: $rejectedCount)")
                    return  // _location bleibt beim letzten akzeptierten Fix
                }

                // Akzeptierten Fix aufbauen
                val rawFix = LocationData(
                    latitude = loc.latitude,
                    longitude = loc.longitude,
                    altitudeM = if (loc.hasAltitude()) loc.altitude else null,
                    accuracyM = accuracy,
                    timestampMs = loc.time
                )
                fixCount++
                acceptedAccuracies.add(accuracy)

                // Median-Fenster aktualisieren
                if (fixWindow.size >= SMOOTHING_WINDOW_SIZE) fixWindow.removeFirst()
                fixWindow.addLast(rawFix)

                // Veroeffentlichen: Median-geglaettet oder roh
                val published = if (appPreferences.gpsSmoothingEnabled && fixWindow.size >= 2) {
                    val lats = fixWindow.map { it.latitude }
                    val lons = fixWindow.map { it.longitude }
                    rawFix.copy(
                        latitude = medianOf(lats),
                        longitude = medianOf(lons)
                    )
                } else {
                    rawFix
                }

                _location.value = published
                Log.d(TAG, "Fix akzeptiert: ${published.latitude}, ${published.longitude} " +
                        "(±${accuracy}m, fixes=$fixCount, window=${fixWindow.size})")
            }
        }
    }

    /**
     * GPS-Tracking starten. Erfordert Location-Permission.
     * Idempotent — mehrfacher Aufruf ist sicher.
     *
     * T53: Intervall aus AppPreferences, LastKnown-Fallback fuer sofortigen Fix.
     */
    @SuppressLint("MissingPermission")
    fun start() {
        if (isTracking) return
        isTracking = true

        // Statistik zuruecksetzen
        fixCount = 0
        rejectedCount = 0
        acceptedAccuracies.clear()
        fixWindow.clear()

        val intervalMs = appPreferences.gpsIntervalSeconds * 1000L
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs / 2)
            .build()

        fusedClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        Log.i(TAG, "GPS-Tracking gestartet (Intervall: ${intervalMs}ms, " +
                "max. Genauigkeit: ${appPreferences.gpsMaxAccuracyMeters}m, " +
                "Smoothing: ${appPreferences.gpsSmoothingEnabled})")

        // LastKnown-Fallback: sofort pubizieren wenn kein Fix vorhanden, jung genug und genau genug.
        // Verhindert null-Koordinaten auf ersten Detektionen (AC1).
        fusedClient.lastLocation.addOnSuccessListener { loc ->
            if (loc == null) return@addOnSuccessListener
            if (_location.value != null) return@addOnSuccessListener  // bereits ein Fix da
            val ageMs = System.currentTimeMillis() - loc.time
            val accuracy = if (loc.hasAccuracy()) loc.accuracy else Float.MAX_VALUE
            if (ageMs <= LASTKNOWN_MAX_AGE_MS && accuracy <= appPreferences.gpsMaxAccuracyMeters) {
                _location.value = LocationData(
                    latitude = loc.latitude,
                    longitude = loc.longitude,
                    altitudeM = if (loc.hasAltitude()) loc.altitude else null,
                    accuracyM = accuracy,
                    timestampMs = loc.time
                )
                Log.i(TAG, "LastKnown-Fallback publiziert: ±${accuracy}m, Alter ${ageMs / 1000}s")
            } else {
                Log.d(TAG, "LastKnown verworfen: Alter ${ageMs / 1000}s, Genauigkeit ±${accuracy}m")
            }
        }
    }

    /**
     * GPS-Tracking stoppen. Idempotent.
     * AC4: Nach Session-Stop keine weiteren Location-Updates.
     */
    fun stop() {
        if (!isTracking) return
        isTracking = false

        fusedClient.removeLocationUpdates(locationCallback)
        fixWindow.clear()
        Log.i(TAG, "GPS-Tracking gestoppt (fixes=$fixCount, verworfen=$rejectedCount)")
    }

    /**
     * GPS-Statistiken der aktuellen Session zusammenfassen (T53).
     * Muss VOR stop() aufgerufen werden (stop() setzt fixWindow zurueck,
     * acceptedAccuracies/fixCount bleiben bis zum naechsten start() erhalten).
     *
     * @param intervalMs Das konfigurierte GPS-Intervall (aus AppPreferences).
     */
    fun buildGpsStats(intervalMs: Long): GpsStats {
        val medianAcc = if (acceptedAccuracies.isEmpty()) {
            0f
        } else {
            medianOf(acceptedAccuracies.map { it.toDouble() }).toFloat()
        }
        return GpsStats(
            fixCount = fixCount,
            rejectedCount = rejectedCount,
            medianAccuracy = medianAcc,
            intervalMs = intervalMs
        )
    }
}
