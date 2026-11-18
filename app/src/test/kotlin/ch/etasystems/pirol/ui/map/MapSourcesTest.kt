package ch.etasystems.pirol.ui.map

import ch.etasystems.pirol.ml.VerificationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

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
        assertSame(SwisstopoFarbeTileSource, tileSourceFor(MapSourceMode.AUTO, 13))
        assertSame(SwisstopoFarbeTileSource, tileSourceFor(MapSourceMode.SWISS_MAP, 8))
        assertSame(SwisstopoLuftbildTileSource, tileSourceFor(MapSourceMode.SWISS_IMAGE, 8))
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
