package ch.etasystems.pirol.ui.map

import androidx.annotation.StringRes
import ch.etasystems.pirol.R
import ch.etasystems.pirol.ml.VerificationStatus
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.TileSourceFactory

/** Waehlbare Kartenquelle (T108), gespeichert als Name in AppPreferences. */
enum class MapSourceMode(@StringRes val labelRes: Int) {
    AUTO(R.string.map_source_auto),
    OSM(R.string.map_source_osm),
    SWISS_MAP(R.string.map_source_swiss_map),
    SWISS_IMAGE(R.string.map_source_swiss_image);

    companion object {
        fun fromName(name: String?): MapSourceMode = entries.firstOrNull { it.name == name } ?: AUTO
    }
}

/** Automatik: OSM unterhalb Zoom 13, ab 13 swisstopo Landeskarte, aber nur wenn die Kartenmitte in der Schweiz liegt (T128). */
const val AUTO_SWISSTOPO_MIN_ZOOM = 13

/** T128: grobe Bounding-Box Schweiz + Liechtenstein (mit kleinem Rand), nur dort liefert swisstopo Kacheln. */
fun isInSwitzerland(lat: Double, lon: Double): Boolean =
    lat in 45.80..47.85 && lon in 5.90..10.55

fun tileSourceFor(mode: MapSourceMode, zoom: Int, lat: Double, lon: Double): ITileSource = when (mode) {
    MapSourceMode.AUTO ->
        if (zoom >= AUTO_SWISSTOPO_MIN_ZOOM && isInSwitzerland(lat, lon)) SwisstopoFarbeTileSource
        else TileSourceFactory.MAPNIK
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
