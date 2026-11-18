package ch.etasystems.pirol.ui.map

import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.MapTileIndex

/**
 * swisstopo Landeskarte farbig (WMTS, EPSG:3857), frei nutzbar mit Quellenangabe.
 * T106: URL-Muster laut WMTS-Capabilities {TileMatrix}/{TileCol}/{TileRow} = {z}/{x}/{y}.
 * Vorher: baseUrl[0] war das erste Zeichen der URL (Char + Int) und x/y waren vertauscht.
 */
object SwisstopoFarbeTileSource : OnlineTileSourceBase(
    "SwisstopoFarbe",
    13, 18,
    256,
    ".jpeg",
    arrayOf("https://wmts.geo.admin.ch/1.0.0/ch.swisstopo.pixelkarte-farbe/default/current/3857/"),
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
