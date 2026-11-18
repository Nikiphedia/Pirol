package ch.etasystems.pirol.ui.map

import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.tan

/**
 * T111: Zoom-abhaengiges Clustering auf Basis von Bildschirm-Pixeln (Web-Mercator).
 * Reine Funktionen ohne Android-Abhaengigkeit, damit unit-testbar.
 */
object MarkerClustering {

    /** Standard-Kachelgroesse von osmdroid (ohne dpi-Skalierung) */
    const val DEFAULT_TILE_SIZE = 256

    /** Web-Mercator: Pixel-X fuer Laengengrad bei gegebenem (auch gebrochenem) Zoom. */
    fun mercatorX(lon: Double, zoom: Double, tileSize: Int = DEFAULT_TILE_SIZE): Double =
        (lon + 180.0) / 360.0 * tileSize * 2.0.pow(zoom)

    /** Web-Mercator: Pixel-Y fuer Breitengrad (auf +-85.05 begrenzt). */
    fun mercatorY(lat: Double, zoom: Double, tileSize: Int = DEFAULT_TILE_SIZE): Double {
        val latC = max(-85.05112878, min(85.05112878, lat))
        val rad = latC * PI / 180.0
        val y = 0.5 - ln(tan(PI / 4.0 + rad / 2.0)) / (2.0 * PI)
        return y * tileSize * 2.0.pow(zoom)
    }

    /**
     * Gruppiert Punkte (Pixelkoordinaten), deren Abstand zum Gruppen-Startpunkt < [thresholdPx] ist.
     * Greedy: Der erste noch freie Punkt wird Startpunkt, alle freien Punkte in Reichweite kommen dazu.
     * Gitter-Buckets (Zellgroesse = Schwelle) halten das Ganze nahe O(n).
     *
     * @return Gruppen als Index-Listen (Reihenfolge nach erstem Index, Indizes aufsteigend)
     */
    fun groupByPixelDistance(xs: DoubleArray, ys: DoubleArray, thresholdPx: Double): List<List<Int>> {
        require(xs.size == ys.size) { "xs und ys muessen gleich lang sein" }
        val n = xs.size
        if (n == 0) return emptyList()
        val cell = max(thresholdPx, 1e-6)
        val grid = HashMap<Long, MutableList<Int>>()
        fun key(cx: Long, cy: Long): Long = cx * 4_000_003L + cy
        for (i in 0 until n) {
            val cx = floor(xs[i] / cell).toLong()
            val cy = floor(ys[i] / cell).toLong()
            grid.getOrPut(key(cx, cy)) { mutableListOf() }.add(i)
        }
        val used = BooleanArray(n)
        val t2 = thresholdPx * thresholdPx
        val result = ArrayList<List<Int>>()
        for (i in 0 until n) {
            if (used[i]) continue
            used[i] = true
            val group = mutableListOf(i)
            val cx = floor(xs[i] / cell).toLong()
            val cy = floor(ys[i] / cell).toLong()
            for (dx in -1L..1L) for (dy in -1L..1L) {
                val bucket = grid[key(cx + dx, cy + dy)] ?: continue
                for (j in bucket) {
                    if (used[j]) continue
                    val ddx = xs[j] - xs[i]
                    val ddy = ys[j] - ys[i]
                    if (ddx * ddx + ddy * ddy < t2) {
                        used[j] = true
                        group.add(j)
                    }
                }
            }
            group.sort()
            result.add(group)
        }
        return result
    }

    /** Gruppiert Geo-Punkte bei gegebenem Zoom; Schwelle in Bildschirm-Pixeln. */
    fun groupByZoom(
        lats: DoubleArray,
        lons: DoubleArray,
        zoom: Double,
        thresholdPx: Double,
        tileSize: Int = DEFAULT_TILE_SIZE
    ): List<List<Int>> {
        val xs = DoubleArray(lons.size) { mercatorX(lons[it], zoom, tileSize) }
        val ys = DoubleArray(lats.size) { mercatorY(lats[it], zoom, tileSize) }
        return groupByPixelDistance(xs, ys, thresholdPx)
    }
}
