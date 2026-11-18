package ch.etasystems.pirol.ui.map

import ch.etasystems.pirol.ml.VerificationStatus
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.TileSourceFactory

/** Waehlbare Kartenquelle (T108), gespeichert als Name in AppPreferences. */
enum class MapSourceMode(val label: String) {
    AUTO("Automatisch"),
    OSM("OpenStreetMap"),
    SWISS_MAP("swisstopo Karte"),
    SWISS_IMAGE("swisstopo Luftbild");

    companion object {
        fun fromName(name: String?): MapSourceMode = entries.firstOrNull { it.name == name } ?: AUTO
    }
}

/** Automatik: OSM unterhalb Zoom 13, ab 13 swisstopo Landeskarte (Verhalten seit T106). */
const val AUTO_SWISSTOPO_MIN_ZOOM = 13

fun tileSourceFor(mode: MapSourceMode, zoom: Int): ITileSource = when (mode) {
    MapSourceMode.AUTO ->
        if (zoom >= AUTO_SWISSTOPO_MIN_ZOOM) SwisstopoFarbeTileSource else TileSourceFactory.MAPNIK
    MapSourceMode.OSM -> TileSourceFactory.MAPNIK
    MapSourceMode.SWISS_MAP -> SwisstopoFarbeTileSource
    MapSourceMode.SWISS_IMAGE -> SwisstopoLuftbildTileSource
}

/** Abgelehnte und ersetzte Beobachtungen erscheinen nicht auf der Karte. */
fun isVisibleOnMap(status: VerificationStatus): Boolean =
    status != VerificationStatus.REJECTED && status != VerificationStatus.REPLACED

/** Regeln fuer den Offline-Download von swisstopo-Kacheln. */
object OfflineTilePlan {
    /** Fair Use swisstopo: maximal 5000 Kacheln pro Download */
    const val MAX_TILES = 5000
    const val MAX_ZOOM = 17
    private const val BYTES_PER_TILE = 25_000L

    /** Zoom von aktuell bis max. 17 (Quelle begrenzt zusaetzlich). */
    fun zoomRange(currentZoom: Int, source: SwisstopoWmtsTileSource): IntRange {
        val last = minOf(MAX_ZOOM, source.maximumZoomLevel)
        val first = currentZoom.coerceIn(source.minimumZoomLevel, last)
        return first..last
    }

    fun isAllowed(tileCount: Int): Boolean = tileCount in 1..MAX_TILES

    fun estimatedMegabytes(tileCount: Int): Int =
        ((tileCount * BYTES_PER_TILE + 999_999L) / 1_000_000L).toInt()
}
