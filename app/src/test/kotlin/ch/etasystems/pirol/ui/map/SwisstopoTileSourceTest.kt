package ch.etasystems.pirol.ui.map

import org.junit.Assert.assertEquals
import org.junit.Test
import org.osmdroid.util.MapTileIndex

/** T106: Kachel-URL muss dem WMTS-Muster {z}/{x}/{y} entsprechen (auf dem Geraet geprueft: 14/8577/5737 = Zuerich). */
class SwisstopoTileSourceTest {

    @Test
    fun `URL enthaelt volle Basisadresse und z-x-y in dieser Reihenfolge`() {
        val index = MapTileIndex.getTileIndex(14, 8577, 5737)
        assertEquals(
            "https://wmts.geo.admin.ch/1.0.0/ch.swisstopo.pixelkarte-farbe/default/current/3857/14/8577/5737.jpeg",
            SwisstopoFarbeTileSource.getTileURLString(index)
        )
    }
}
