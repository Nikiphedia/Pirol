package ch.etasystems.pirol.ui.map

import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.MapTileIndex

object SwisstopoFarbeTileSource : OnlineTileSourceBase(
    "SwisstopoFarbe",
    13, 18,
    256,
    ".jpeg",
    arrayOf("https://wmts.geo.admin.ch/1.0.0/ch.swisstopo.pixelkarte-farbe/default/current/3857/")
) {
    override fun getTileURLString(pMapTileIndex: Long): String {
        return (baseUrl[0]
            + MapTileIndex.getZoom(pMapTileIndex) + "/"
            + MapTileIndex.getY(pMapTileIndex) + "/"
            + MapTileIndex.getX(pMapTileIndex)
            + ".jpeg")
    }
}
