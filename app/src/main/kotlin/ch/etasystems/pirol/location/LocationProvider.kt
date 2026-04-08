package ch.etasystems.pirol.location

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import android.util.Log
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
 * GPS-Location-Provider basierend auf FusedLocationProviderClient.
 * Stellt aktuelle Position als StateFlow bereit.
 *
 * Lifecycle: Singleton via Koin. start()/stop() werden vom LiveViewModel gesteuert.
 */
class LocationProvider(private val context: Context) {

    companion object {
        private const val TAG = "LocationProvider"
        private const val UPDATE_INTERVAL_MS = 10_000L    // 10s Normal-Intervall
        private const val FASTEST_INTERVAL_MS = 5_000L    // 5s schnellstes Intervall
    }

    // Aktuelle Position als StateFlow (null = noch keine Position)
    private val _location = MutableStateFlow<LocationData?>(null)
    val location: StateFlow<LocationData?> = _location.asStateFlow()

    private val fusedClient = LocationServices.getFusedLocationProviderClient(context)
    private var isTracking = false

    // LocationCallback fuer Updates
    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { loc ->
                _location.value = LocationData(
                    latitude = loc.latitude,
                    longitude = loc.longitude,
                    altitudeM = if (loc.hasAltitude()) loc.altitude else null,
                    accuracyM = if (loc.hasAccuracy()) loc.accuracy else null,
                    timestampMs = loc.time
                )
                Log.d(TAG, "Position aktualisiert: ${loc.latitude}, ${loc.longitude} (±${loc.accuracy}m)")
            }
        }
    }

    /**
     * GPS-Tracking starten. Erfordert Location-Permission.
     * Idempotent — mehrfacher Aufruf ist sicher.
     */
    @SuppressLint("MissingPermission")
    fun start() {
        if (isTracking) return
        isTracking = true

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, UPDATE_INTERVAL_MS)
            .setMinUpdateIntervalMillis(FASTEST_INTERVAL_MS)
            .build()

        fusedClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        Log.i(TAG, "GPS-Tracking gestartet (Intervall: ${UPDATE_INTERVAL_MS}ms)")
    }

    /**
     * GPS-Tracking stoppen. Idempotent.
     */
    fun stop() {
        if (!isTracking) return
        isTracking = false

        fusedClient.removeLocationUpdates(locationCallback)
        Log.i(TAG, "GPS-Tracking gestoppt")
    }
}
