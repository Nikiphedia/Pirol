package ch.etasystems.pirol.ui.map

import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.XYTileSource

/**
 * Verfuegbare Karten-Quellen fuer PIROL.
 * OSM als Default, swisstopo Landeskarte und Luftbild als Optionen.
 */
enum class MapTileSource(val id: String, val displayName: String) {
    OSM("osm", "OpenStreetMap"),
    SWISSTOPO_MAP("swisstopo_map", "swisstopo Landeskarte"),
    SWISSTOPO_AERIAL("swisstopo_aerial", "swisstopo Luftbild");

    companion object {
        fun fromId(id: String): MapTileSource =
            entries.find { it.id == id } ?: OSM
    }
}

/**
 * Erzeugt osmdroid TileSource-Instanzen fuer die konfigurierten Quellen.
 *
 * swisstopo WMTS-Endpunkt: https://wmts.geo.admin.ch/1.0.0/{layer}/default/current/3857/{z}/{x}/{y}.jpeg
 * osmdroid XYTileSource baut URLs als: baseUrl + z + "/" + x + "/" + y + extension
 * Daher muss baseUrl auf "/" enden und die Extension ".jpeg" sein.
 */
object PirolTileSourceFactory {

    /** Standard-OSM (MAPNIK) als OnlineTileSourceBase */
    private val OSM_MAPNIK = org.osmdroid.tileprovider.tilesource.TileSourceFactory.MAPNIK

    fun create(source: MapTileSource, apiKey: String = ""): OnlineTileSourceBase {
        return when (source) {
            MapTileSource.OSM -> OSM_MAPNIK

            MapTileSource.SWISSTOPO_MAP -> createSwisstopoSource(
                name = "swisstopo_map",
                layer = "ch.swisstopo.pixelkarte-farbe",
                apiKey = apiKey
            )

            MapTileSource.SWISSTOPO_AERIAL -> createSwisstopoSource(
                name = "swisstopo_aerial",
                layer = "ch.swisstopo.swissimage",
                apiKey = apiKey
            )
        }
    }

    /**
     * Erstellt eine XYTileSource fuer swisstopo WMTS.
     * URL-Aufbau: baseUrl + z + "/" + x + "/" + y + ".jpeg"
     * Ergebnis: https://wmts.geo.admin.ch/1.0.0/{layer}/default/current/3857/{z}/{x}/{y}.jpeg
     */
    private fun createSwisstopoSource(
        name: String,
        layer: String,
        apiKey: String
    ): XYTileSource {
        // Base-URL endet auf "/" damit osmdroid korrekt z/x/y anhaengt
        val baseUrl = "https://wmts.geo.admin.ch/1.0.0/$layer/default/current/3857/"

        return XYTileSource(
            name,
            7,      // minZoom — swisstopo hat keine Tiles unter Zoom 7
            18,     // maxZoom
            256,    // tileSizePixels
            ".jpeg",
            arrayOf(baseUrl)
        )
    }
}
