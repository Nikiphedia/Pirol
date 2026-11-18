package ch.etasystems.pirol.ui.map

import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.MapTileIndex

/**
 * swisstopo WMTS-Kachelquelle (EPSG:3857), frei nutzbar mit Quellenangabe.
 * T106: URL-Muster laut WMTS-Capabilities {TileMatrix}/{TileCol}/{TileRow} = {z}/{x}/{y}.
 * T108: Basisklasse fuer Landeskarte und Luftbild. Der Name steuert den osmdroid-Cache-Schluessel,
 * daher muss er je Layer eindeutig sein.
 */
open class SwisstopoWmtsTileSource(name: String, layer: String, minZoom: Int, maxZoom: Int) :
    OnlineTileSourceBase(
        name,
        minZoom, maxZoom,
        256,
        ".jpeg",
        arrayOf("https://wmts.geo.admin.ch/1.0.0/$layer/default/current/3857/"),
        "© swisstopo"
    ) {
    override fun getTileURLString(pMapTileIndex: Long): String {
        return baseUrl +
            MapTileIndex.getZoom(pMapTileIndex) + "/" +
            MapTileIndex.getX(pMapTileIndex) + "/" +
            MapTileIndex.getY(pMapTileIndex) +
            mImageFilenameEnding
    }
}

// Zoom-Grenzen per curl geprueft (T108): pixelkarte-farbe liefert bis z19, swissimage bis z20.
// Min-Zoom 7: darunter ist die Schweiz kleiner als der Bildschirm; verhindert zugleich,
// dass im Automatik-Modus der Wechsel von/zu OSM das Auszoomen blockiert.

/** swisstopo Landeskarte farbig */
object SwisstopoFarbeTileSource :
    SwisstopoWmtsTileSource("SwisstopoFarbe", "ch.swisstopo.pixelkarte-farbe", 7, 19)

/** swisstopo Luftbild (SWISSIMAGE) */
object SwisstopoLuftbildTileSource :
    SwisstopoWmtsTileSource("SwisstopoLuftbild", "ch.swisstopo.swissimage", 7, 20)
