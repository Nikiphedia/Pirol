package ch.etasystems.pirol.ml

import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** T218: Verknuepfung des Geomodel-Filters mit einer Artenliste. */
enum class GeoCombine {
    /** nur Geomodel (die Liste dient nur als Ersatz ohne Position) */
    GEO_ONLY,
    /** Liste UND Geomodel */
    INTERSECT,
    /** Liste ODER Geomodel */
    UNION;

    companion object {
        fun parse(name: String?): GeoCombine = entries.firstOrNull { it.name == name } ?: GEO_ONLY
    }
}

/**
 * T218: Ergebnis einer Geomodel-Abfrage als kanonische Artschluessel.
 * [known] = Arten, die das Geomodel kennt; [allowed] = davon Score >= Schwelle.
 */
class GeoMask(val known: Set<String>, val allowed: Set<String>) {
    /** Unbekannte Arten (auch Geraeuschklassen) passieren immer; bekannte nur ueber der Schwelle. */
    fun passes(key: String): Boolean = key !in known || key in allowed
}

/** T218: Position/Zeit, fuer die zuletzt gerechnet wurde. */
data class GeoState(val lat: Double, val lon: Double, val week: Int, val atMs: Long)

/** T218: Reine Logik des Geomodel-Filters (ohne Android, unit-testbar). */
object GeoLogic {

    const val REGION_ID = "auto_geo"
    const val WEEKS = 48
    const val DEFAULT_THRESHOLD = 0.03f
    const val MIN_THRESHOLD = 0.01f
    const val MAX_THRESHOLD = 0.15f

    /** Ortswechsel, ab dem neu gerechnet wird (km) */
    const val MOVE_KM = 10.0
    /** Mindestabstand zwischen zwei Berechnungen (ms) */
    const val MIN_INTERVAL_MS = 60_000L
    /** Gespeicherte Position gilt hoechstens so lange (ms) */
    const val SAVED_MAX_AGE_MS = 30L * 24 * 3600 * 1000

    /** BirdNET-Woche 1-48 (4 pro Monat): Tage 1-7, 8-14, 15-21, ab 22 = Woche 1-4 des Monats. */
    fun birdnetWeek(month: Int, day: Int): Int {
        val inMonth = when {
            day <= 7 -> 1
            day <= 14 -> 2
            day <= 21 -> 3
            else -> 4
        }
        return (month - 1) * 4 + inMonth
    }

    fun birdnetWeek(date: LocalDate): Int = birdnetWeek(date.monthValue, date.dayOfMonth)

    /** Maske aus Namen (Index = Modellausgabe) und Scores; [keyOf] kanonisiert (Alias). */
    fun buildMask(
        names: List<String>,
        scores: FloatArray,
        threshold: Float,
        keyOf: (String) -> String = SpeciesKey::of
    ): GeoMask {
        val known = HashSet<String>(names.size * 2)
        val allowed = HashSet<String>()
        val n = min(names.size, scores.size)
        for (i in 0 until n) {
            val key = keyOf(names[i])
            known.add(key)
            if (scores[i] >= threshold) allowed.add(key)
        }
        return GeoMask(known, allowed)
    }

    /** Jahresliste: je Art das Maximum ueber alle Wochen. */
    fun yearMax(perWeek: List<FloatArray>): FloatArray {
        val out = FloatArray(perWeek.firstOrNull()?.size ?: 0)
        for (w in perWeek) for (i in out.indices) out[i] = max(out[i], w[i])
        return out
    }

    /** Plausibilitaet mit Maske; [listOk] = Ergebnis der Listenpruefung. Ohne Maske zaehlt nur die Liste. */
    fun plausible(key: String, mask: GeoMask?, listOk: Boolean, combine: GeoCombine): Boolean {
        if (mask == null) return listOk
        val geoOk = mask.passes(key)
        return when (combine) {
            GeoCombine.GEO_ONLY -> geoOk
            GeoCombine.INTERSECT -> geoOk && listOk
            GeoCombine.UNION -> geoOk || listOk
        }
    }

    /** Grosskreisabstand in km. */
    fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * asin(sqrt(a))
    }

    /** Neu rechnen? Erste Berechnung immer; sonst bei neuer Woche oder > 10 km, hoechstens 1x/min. */
    fun shouldRecompute(last: GeoState?, lat: Double, lon: Double, week: Int, nowMs: Long): Boolean {
        if (last == null) return true
        if (abs(nowMs - last.atMs) < MIN_INTERVAL_MS) return false
        return week != last.week || distanceKm(last.lat, last.lon, lat, lon) > MOVE_KM
    }

    /** Quelle der verwendeten Position */
    enum class PositionSource { GPS, SAVED, NONE }

    /** Position waehlen: frischer Fix, sonst gespeicherte (hoechstens 30 Tage), sonst keine. */
    fun choosePosition(
        fresh: Pair<Double, Double>?,
        saved: Pair<Double, Double>?,
        savedAtMs: Long,
        nowMs: Long
    ): Pair<PositionSource, Pair<Double, Double>?> = when {
        fresh != null -> PositionSource.GPS to fresh
        saved != null && savedAtMs > 0 && nowMs - savedAtMs in 0..SAVED_MAX_AGE_MS -> PositionSource.SAVED to saved
        else -> PositionSource.NONE to null
    }
}
