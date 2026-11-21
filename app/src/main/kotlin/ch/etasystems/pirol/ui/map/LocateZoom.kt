package ch.etasystems.pirol.ui.map

import kotlin.math.cos
import kotlin.math.ln

/** Reine Zoomberechnung fuer den Standort-Knopf (T175). */
object LocateZoom {
    const val TARGET_HEIGHT_M = 200.0
    const val FALLBACK_ZOOM = 18.0
    private const val EQUATOR_M_PER_PX = 156543.03392 // Web-Mercator, Kachel 256 px, Zoom 0

    /**
     * Zoomstufe, bei der [mapHeightPx] Pixel ca. [heightM] Meter abdecken.
     * Bei unbrauchbarer Hoehe (vor Layout) oder Breite: [FALLBACK_ZOOM].
     */
    fun zoomForHeight(
        latitude: Double,
        mapHeightPx: Int,
        maxZoom: Double,
        heightM: Double = TARGET_HEIGHT_M
    ): Double {
        if (mapHeightPx <= 0 || latitude.isNaN()) return FALLBACK_ZOOM.coerceAtMost(maxZoom)
        val mPerPxAtZoom0 = EQUATOR_M_PER_PX * cos(Math.toRadians(latitude))
        val zoom = ln(mPerPxAtZoom0 * mapHeightPx / heightM) / ln(2.0)
        return zoom.coerceIn(0.0, maxZoom)
    }
}
