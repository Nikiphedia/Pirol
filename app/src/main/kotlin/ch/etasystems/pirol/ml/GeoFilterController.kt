package ch.etasystems.pirol.ml

import android.content.Context
import android.util.Log
import ch.etasystems.pirol.data.AppPreferences
import ch.etasystems.pirol.data.repository.SpeciesFilterInfo
import ch.etasystems.pirol.location.LocationData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * T218: Berechnet die Geomodel-Maske fuer den Filter "Automatisch (Standort + Woche)" und
 * schreibt sie in den [RegionalSpeciesFilter]. Gerechnet wird beim Session-Start sowie bei
 * Wochenwechsel oder Ortswechsel > 10 km (hoechstens 1x/min, siehe [GeoLogic.shouldRecompute]).
 * Die ORT-Session des Geomodels bleibt nie offen.
 */
class GeoFilterController(
    private val context: Context,
    private val prefs: AppPreferences,
    private val filter: RegionalSpeciesFilter
) {
    companion object {
        private const val TAG = "GeoFilterController"
    }

    private val mutex = Mutex()
    private var last: GeoState? = null
    @Volatile
    var info: SpeciesFilterInfo? = null
        private set

    /** Region "auto_geo" gewaehlt? */
    private fun active() = prefs.regionFilter == GeoLogic.REGION_ID

    /** Session-Start: immer rechnen (nach dem Startfix). null = Geomodel-Filter nicht gewaehlt. */
    suspend fun prepare(fresh: LocationData?, nowMs: Long): SpeciesFilterInfo? =
        mutex.withLock { compute(fresh, nowMs, force = true) }

    /** Waehrend der Aufnahme: nur bei Wochen- oder Ortswechsel neu rechnen. */
    suspend fun refresh(loc: LocationData?, nowMs: Long) {
        if (loc == null || !active()) return
        mutex.withLock { compute(loc, nowMs, force = false) }
    }

    private suspend fun compute(fresh: LocationData?, nowMs: Long, force: Boolean): SpeciesFilterInfo? {
        if (!active()) {
            // Auswahl wurde geaendert: Filter mit der neuen Region laden, Geomodel-Zustand verwerfen
            if (filter.isAutoGeo()) prefs.regionFilter?.let { filter.loadRegion(it) }
            info = null
            last = null
            return null
        }
        if (force) filter.loadRegion(GeoLogic.REGION_ID)  // Begleitliste/Verknuepfung aus den Einstellungen
        val combine = GeoCombine.parse(prefs.geoCombine)
        val companion = prefs.geoCompanionRegion
        val date = Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault()).toLocalDate()
        val week = GeoLogic.birdnetWeek(date)

        val saved = if (prefs.geoLastAtMs > 0) prefs.geoLastLat to prefs.geoLastLon else null
        val (source, pos) = GeoLogic.choosePosition(
            fresh?.let { it.latitude to it.longitude }, saved, prefs.geoLastAtMs, nowMs
        )
        if (pos == null || !GeoModelFiles.isInstalled(context)) {
            // Ersatz: Begleitliste ohne Geomodel
            filter.setGeoMask(null, combine)
            last = null
            val i = SpeciesFilterInfo(source = "fallback", fallbackRegion = companion)
            info = i
            Log.i(TAG, "T218_GEO fallback region=$companion installed=${GeoModelFiles.isInstalled(context)}")
            return i
        }
        val (lat, lon) = pos
        if (!force && !GeoLogic.shouldRecompute(last, lat, lon, week, nowMs)) return info

        val yearly = prefs.geoYearly
        val threshold = prefs.geoThreshold.coerceIn(GeoLogic.MIN_THRESHOLD, GeoLogic.MAX_THRESHOLD)
        return try {
            val t0 = System.currentTimeMillis()
            val mask = withContext(Dispatchers.Default) {
                SpeciesKey.load(context)
                val model = GeoSpeciesModel(GeoModelFiles.modelFile(context), GeoModelFiles.indexFile(context))
                val names = model.loadNames()
                val weeks = if (yearly) IntArray(GeoLogic.WEEKS) { it + 1 } else intArrayOf(week)
                val scores = GeoLogic.yearMax(model.predict(lat, lon, weeks))
                GeoLogic.buildMask(names, scores, threshold)
            }
            filter.setGeoMask(mask, combine)
            last = GeoState(lat, lon, week, nowMs)
            if (source == GeoLogic.PositionSource.GPS) {
                prefs.geoLastLat = lat; prefs.geoLastLon = lon; prefs.geoLastAtMs = nowMs
            }
            Log.i(TAG, "T218_GEO ms=${System.currentTimeMillis() - t0} week=$week yearly=$yearly " +
                "allowed=${mask.allowed.size}/${mask.known.size} source=$source")
            SpeciesFilterInfo(
                source = if (source == GeoLogic.PositionSource.GPS) "gps" else "saved",
                latitude = round2(lat), longitude = round2(lon), week = if (yearly) null else week,
                threshold = threshold, combine = combine.name, yearly = yearly,
                model = GeoModelFiles.VERSION, fallbackRegion = companion
            ).also { info = it }
        } catch (e: Exception) {
            Log.w(TAG, "Geomodel nicht berechenbar: ${e.message}", e)
            filter.setGeoMask(null, combine)
            last = null
            SpeciesFilterInfo(source = "fallback", fallbackRegion = companion).also { info = it }
        }
    }

    private fun round2(v: Double) = (v * 100).roundToInt() / 100.0
}
