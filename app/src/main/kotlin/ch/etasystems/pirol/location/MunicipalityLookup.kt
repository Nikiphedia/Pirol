package ch.etasystems.pirol.location

import android.content.Context
import android.location.Geocoder
import android.os.Build
import android.util.Log
import ch.etasystems.pirol.data.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume

/**
 * T195: Gemeinde zu einer GPS-Position ("Name KT").
 * Hauptweg offline ueber die Asset-Datei regions/ch_municipalities.bin (nur Schweiz/FL),
 * Rueckfall online ueber den Android-Geocoder (sendet Koordinaten an Google) nur, wenn die
 * Einstellung pirol_geocoder_fallback_enabled eingeschaltet ist (T262, Default aus).
 */
class MunicipalityLookup(context: Context) {

    private val appContext = context.applicationContext
    private val loadMutex = Mutex()
    @Volatile private var index: MunicipalityIndex? = null

    private suspend fun loadIndex(): MunicipalityIndex? = withContext(Dispatchers.IO) {
        index ?: loadMutex.withLock {
            index ?: try {
                appContext.assets.open(ASSET_PATH).use { MunicipalityIndex.read(it) }.also { index = it }
            } catch (e: Exception) {
                Log.w(TAG, "Gemeinde-Asset nicht lesbar: ${e.message}")
                null
            }
        }
    }

    /** Nur offline. */
    suspend fun lookupOffline(lat: Double, lon: Double): String? {
        val idx = loadIndex() ?: return null
        val p = Lv95.fromWgs84(lat, lon)
        return withContext(Dispatchers.Default) { idx.find(p.e, p.n) }
    }

    /** Offline zuerst, dann Geocoder (nur bei eingeschalteter Option); null = nicht ermittelbar. */
    suspend fun lookup(lat: Double, lon: Double): String? =
        resolveMunicipality(
            fallbackEnabled = AppPreferences(appContext).geocoderFallbackEnabled,
            offline = { lookupOffline(lat, lon) },
            online = { lookupOnline(lat, lon) }
        )

    private suspend fun lookupOnline(lat: Double, lon: Double): String? {
        if (!Geocoder.isPresent()) return null
        return try {
            val geocoder = Geocoder(appContext, Locale.getDefault())
            val address = if (Build.VERSION.SDK_INT >= 33) {
                suspendCancellableCoroutine { cont ->
                    geocoder.getFromLocation(lat, lon, 1, object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<android.location.Address>) {
                            cont.resume(addresses.firstOrNull())
                        }
                        override fun onError(errorMessage: String?) {
                            cont.resume(null)
                        }
                    })
                }
            } else {
                withContext(Dispatchers.IO) {
                    @Suppress("DEPRECATION")
                    geocoder.getFromLocation(lat, lon, 1)?.firstOrNull()
                }
            }
            if (address == null) return null
            val name = address.locality ?: address.subAdminArea ?: return null
            val cc = address.countryCode
            if (cc.isNullOrBlank()) name else "$name $cc"
        } catch (e: Exception) {
            Log.w(TAG, "Geocoder fehlgeschlagen: ${e.message}")
            null
        }
    }

    companion object {
        private const val TAG = "MunicipalityLookup"
        const val ASSET_PATH = "regions/ch_municipalities.bin"
    }
}

/** T262: Entscheidungslogik. Online-Rueckfall wird nur aufgerufen, wenn er erlaubt ist. */
internal suspend fun resolveMunicipality(
    fallbackEnabled: Boolean,
    offline: suspend () -> String?,
    online: suspend () -> String?
): String? = offline() ?: if (fallbackEnabled) online() else null
