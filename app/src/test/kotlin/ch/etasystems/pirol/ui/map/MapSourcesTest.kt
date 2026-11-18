package ch.etasystems.pirol.ui.map

import ch.etasystems.pirol.ml.VerificationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.osmdroid.tileprovider.tilesource.TileSourceFactory

/** T108: Kachel-Limit, Zoom-Bereich, Quellenwahl und Marker-Filter. */
class MapSourcesTest {

    @Test
    fun `Kachelzahl ueber dem Limit wird abgelehnt`() {
        assertTrue(OfflineTilePlan.isAllowed(5000))
        assertFalse(OfflineTilePlan.isAllowed(5001))
        assertFalse(OfflineTilePlan.isAllowed(0))
    }

    @Test
    fun `Schaetzung rundet auf ganze MB auf`() {
        assertEquals(125, OfflineTilePlan.estimatedMegabytes(5000))
        assertEquals(1, OfflineTilePlan.estimatedMegabytes(1))
    }

    @Test
    fun `Zoom-Bereich endet bei 17 und beginnt beim aktuellen Zoom`() {
        assertEquals(14..17, OfflineTilePlan.zoomRange(14, SwisstopoFarbeTileSource))
        assertEquals(17..17, OfflineTilePlan.zoomRange(19, SwisstopoLuftbildTileSource))
        assertEquals(7..17, OfflineTilePlan.zoomRange(3, SwisstopoFarbeTileSource))
    }

    @Test
    fun `Automatik waehlt swisstopo ab Zoom 13, feste Modi ignorieren den Zoom`() {
        assertSame(SwisstopoFarbeTileSource, tileSourceFor(MapSourceMode.AUTO, 13, ZH_LAT, ZH_LON))
        assertSame(TileSourceFactory.MAPNIK, tileSourceFor(MapSourceMode.AUTO, 12, ZH_LAT, ZH_LON))
        assertSame(SwisstopoFarbeTileSource, tileSourceFor(MapSourceMode.SWISS_MAP, 8, BERLIN_LAT, BERLIN_LON))
        assertSame(SwisstopoLuftbildTileSource, tileSourceFor(MapSourceMode.SWISS_IMAGE, 8, BERLIN_LAT, BERLIN_LON))
    }

    @Test
    fun `Automatik nutzt ausserhalb der Schweiz auch bei hohem Zoom OSM`() {
        assertSame(TileSourceFactory.MAPNIK, tileSourceFor(MapSourceMode.AUTO, 16, BERLIN_LAT, BERLIN_LON))
        assertSame(TileSourceFactory.MAPNIK, tileSourceFor(MapSourceMode.AUTO, 16, 48.1, 11.6))
    }

    @Test
    fun `Schweiz-Bounding-Box enthaelt CH-Orte und schliesst Nachbarn aus`() {
        assertTrue(isInSwitzerland(ZH_LAT, ZH_LON))
        assertTrue(isInSwitzerland(46.0, 8.95))   // Lugano
        assertTrue(isInSwitzerland(47.14, 9.52))  // Vaduz
        assertFalse(isInSwitzerland(BERLIN_LAT, BERLIN_LON))
        assertFalse(isInSwitzerland(45.4, 9.2))   // Mailand
        assertFalse(isInSwitzerland(48.85, 2.35)) // Paris
    }

    private companion object {
        const val ZH_LAT = 47.3769
        const val ZH_LON = 8.5417
        const val BERLIN_LAT = 52.52
        const val BERLIN_LON = 13.40
    }

    @Test
    fun `Unbekannter gespeicherter Modus faellt auf Automatik zurueck`() {
        assertEquals(MapSourceMode.AUTO, MapSourceMode.fromName("quatsch"))
        assertEquals(MapSourceMode.SWISS_IMAGE, MapSourceMode.fromName("SWISS_IMAGE"))
    }

    @Test
    fun `Abgelehnte und ersetzte Beobachtungen sind nicht sichtbar`() {
        assertFalse(isVisibleOnMap(VerificationStatus.REJECTED))
        assertFalse(isVisibleOnMap(VerificationStatus.REPLACED))
        assertTrue(isVisibleOnMap(VerificationStatus.CONFIRMED))
        assertTrue(isVisibleOnMap(VerificationStatus.UNCERTAIN))
    }

    @Test
    fun `Luftbild-URL nutzt swissimage-Layer`() {
        val index = org.osmdroid.util.MapTileIndex.getTileIndex(18, 137237, 91790)
        assertEquals(
            "https://wmts.geo.admin.ch/1.0.0/ch.swisstopo.swissimage/default/current/3857/18/137237/91790.jpeg",
            SwisstopoLuftbildTileSource.getTileURLString(index)
        )
    }
}
