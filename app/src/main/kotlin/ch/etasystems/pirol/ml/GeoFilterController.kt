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
 * T218: Berechnet die Geomodel-Maske fuer Artgruppen auf "Auto" (Standort + Woche) und
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

    /** T232: Steht eine Artgruppe auf Auto? */
    private fun active() = prefs.speciesGroups.usesAuto()

    /** T246: Ersatzlisten der Auto-Gruppen fuer session.json */
    private fun fallbacks(sel: SpeciesGroupSelection): Map<String, String>? =
        RegionalSpeciesFilter.autoFallbacks(sel, prefs.groupFallbackBirds).ifEmpty { null }

    /** Session-Start: immer rechnen (nach dem Startfix). Liefert die Filter-Angaben fuer session.json. */
    suspend fun prepare(fresh: LocationData?, nowMs: Long): SpeciesFilterInfo? =
        mutex.withLock { compute(fresh, nowMs, force = true) }

    /** Waehrend der Aufnahme: nur bei Wochen- oder Ortswechsel neu rechnen. */
    suspend fun refresh(loc: LocationData?, nowMs: Long) {
        if (loc == null || !active()) return
        mutex.withLock { compute(loc, nowMs, force = false) }
    }

    private suspend fun compute(fresh: LocationData?, nowMs: Long, force: Boolean): SpeciesFilterInfo? {
        val sel = prefs.speciesGroups
        val groups = sel.toMap()
        if (!sel.usesAuto()) {
            // Auswahl wurde geaendert: Gruppen neu laden, Geomodel-Zustand verwerfen
            if (force || filter.isAutoGeo()) filter.loadGroups()
            filter.setGeoMask(null)
            last = null
            // T232: Gruppenwahl auch ohne Geomodel in session.json vermerken
            return SpeciesFilterInfo(source = "list", groups = groups).also { info = it }
        }
        if (force) filter.loadGroups()  // Gruppenwahl der Einstellungen
        val fallbacks = fallbacks(sel)
        val date = Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault()).toLocalDate()
        val week = GeoLogic.birdnetWeek(date)

        val saved = if (prefs.geoLastAtMs > 0) prefs.geoLastLat to prefs.geoLastLon else null
        val (source, pos) = GeoLogic.choosePosition(
            fresh?.let { it.latitude to it.longitude }, saved, prefs.geoLastAtMs, nowMs
        )
        if (pos == null || !GeoModelFiles.isInstalled(context)) {
            // Ersatz: Ersatzliste je Auto-Gruppe ohne Geomodel
            filter.setGeoMask(null)
            last = null
            val i = SpeciesFilterInfo(source = "fallback", fallbacks = fallbacks, groups = groups)
            info = i
            Log.i(TAG, "T218_GEO fallback lists=$fallbacks installed=${GeoModelFiles.isInstalled(context)}")
            return i
        }
        val (lat, lon) = pos
        if (!force && !GeoLogic.shouldRecompute(last, lat, lon, week, nowMs)) return info

        val yearly = prefs.geoYearly
        val threshold = prefs.geoThreshold.coerceIn(GeoLogic.MIN_THRESHOLD, GeoLogic.MAX_THRESHOLD)
        return try {
            val t0 = System.currentTimeMillis()
            val mask = computeMask(lat, lon, week, yearly, threshold)
            filter.setGeoMask(mask)
            last = GeoState(lat, lon, week, nowMs)
            if (source == GeoLogic.PositionSource.GPS) {
                prefs.geoLastLat = lat; prefs.geoLastLon = lon; prefs.geoLastAtMs = nowMs
            }
            Log.i(TAG, "T218_GEO ms=${System.currentTimeMillis() - t0} week=$week yearly=$yearly " +
                "allowed=${mask.allowed.size}/${mask.known.size} source=$source")
            SpeciesFilterInfo(
                source = if (source == GeoLogic.PositionSource.GPS) "gps" else "saved",
                latitude = round2(lat), longitude = round2(lon), week = if (yearly) null else week,
                threshold = threshold, yearly = yearly,
                model = GeoModelFiles.VERSION, fallbacks = fallbacks, groups = groups
            ).also { info = it }
        } catch (e: Exception) {
            Log.w(TAG, "Geomodel nicht berechenbar: ${e.message}", e)
            filter.setGeoMask(null)
            last = null
            SpeciesFilterInfo(source = "fallback", fallbacks = fallbacks, groups = groups).also { info = it }
        }
    }

    /** Geomodel-Maske fuer Position und Woche (ohne Zustand; wirft, wenn das Modell fehlt/unlesbar ist) */
    private suspend fun computeMask(lat: Double, lon: Double, week: Int, yearly: Boolean, threshold: Float): GeoMask =
        withContext(Dispatchers.Default) {
            SpeciesKey.load(context)
            val model = GeoSpeciesModel(GeoModelFiles.modelFile(context), GeoModelFiles.indexFile(context))
            val names = model.loadNames()
            val weeks = if (yearly) IntArray(GeoLogic.WEEKS) { it + 1 } else intArrayOf(week)
            val scores = GeoLogic.yearMax(model.predict(lat, lon, weeks))
            GeoLogic.buildMask(names, scores, threshold)
        }

    /**
     * T221: Geomodel-Filter fuer einen Rescan in [target] setzen — feste Position und Datum der Session,
     * Einstellungen (Schwelle, Jahr, Ersatzliste) wie live. [target] traegt bereits die Gruppenregeln von
     * [selection]. Aendert weder den Live-Filter noch die gespeicherte Position. Ohne Position oder Modell:
     * Ersatzliste je Auto-Gruppe.
     */
    suspend fun applyForRescan(
        target: RegionalSpeciesFilter, lat: Double?, lon: Double?, atMs: Long,
        selection: SpeciesGroupSelection
    ): SpeciesFilterInfo {
        val groups = selection.toMap()
        val fallbacks = fallbacks(selection)
        if (lat == null || lon == null || !GeoModelFiles.isInstalled(context)) {
            target.setGeoMask(null)
            return SpeciesFilterInfo(source = "fallback", fallbacks = fallbacks, groups = groups)
        }
        val week = GeoLogic.birdnetWeek(Instant.ofEpochMilli(atMs).atZone(ZoneId.systemDefault()).toLocalDate())
        val yearly = prefs.geoYearly
        val threshold = prefs.geoThreshold.coerceIn(GeoLogic.MIN_THRESHOLD, GeoLogic.MAX_THRESHOLD)
        return try {
            target.setGeoMask(computeMask(lat, lon, week, yearly, threshold))
            SpeciesFilterInfo(
                source = "session", latitude = round2(lat), longitude = round2(lon), week = if (yearly) null else week,
                threshold = threshold, yearly = yearly,
                model = GeoModelFiles.VERSION, fallbacks = fallbacks, groups = groups
            )
        } catch (e: Exception) {
            Log.w(TAG, "Geomodel (Rescan) nicht berechenbar: ${e.message}", e)
            target.setGeoMask(null)
            SpeciesFilterInfo(source = "fallback", fallbacks = fallbacks, groups = groups)
        }
    }

    private fun round2(v: Double) = (v * 100).roundToInt() / 100.0
}
