package ch.etasystems.pirol.data.export

import ch.etasystems.pirol.data.repository.FileSessionFiles
import ch.etasystems.pirol.location.TrackLogic
import ch.etasystems.pirol.location.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class GpxExporterTest {
    @get:Rule val tmp = TemporaryFolder()

    private val points = listOf(
        TrackPoint("2026-11-22T10:00:00+01:00", 47.3769, 8.5417, 4.5f, 408.25),
        TrackPoint("2026-11-22T10:01:00+01:00", 47.3770123, 8.5418, 6f, null),
    )

    private fun parse(xml: String) = DocumentBuilderFactory.newInstance()
        .apply { isNamespaceAware = true }
        .newDocumentBuilder()
        .parse(xml.byteInputStream(Charsets.UTF_8))

    @Test fun build_isValidXml_withTrkptsInUtc() {
        val doc = parse(GpxExporter.build(points, "2026-11-22_abc123"))
        val root = doc.documentElement
        assertEquals("gpx", root.localName)
        assertEquals("http://www.topografix.com/GPX/1/1", root.namespaceURI)
        assertEquals("1.1", root.getAttribute("version"))
        assertEquals(1, doc.getElementsByTagNameNS("*", "trk").length)
        assertEquals(1, doc.getElementsByTagNameNS("*", "trkseg").length)
        val pts = doc.getElementsByTagNameNS("*", "trkpt")
        assertEquals(2, pts.length)
        val first = pts.item(0) as Element
        assertEquals("47.3769000", first.getAttribute("lat"))
        assertEquals("8.5417000", first.getAttribute("lon"))
        assertEquals("408.3", first.getElementsByTagNameNS("*", "ele").item(0).textContent)
        assertEquals("2026-11-22T09:00:00Z", first.getElementsByTagNameNS("*", "time").item(0).textContent)
        val second = pts.item(1) as Element
        assertEquals(0, second.getElementsByTagNameNS("*", "ele").length)
        assertEquals("47.3770123", second.getAttribute("lat"))
    }

    @Test fun build_eleBeforeTime() {
        val xml = GpxExporter.build(points.take(1), "x")
        val trkpt = xml.substring(xml.indexOf("<trkpt"))
        assertTrue(trkpt.indexOf("<ele>") in 0 until trkpt.indexOf("<time>"))
    }

    @Test fun escaping_nameAndDescription() {
        val name = "A&B <\"Test\"> 'x'\u0001"
        val xml = GpxExporter.build(points, name, "Sparse & <dünn>")
        val doc = parse(xml)
        val names = doc.getElementsByTagNameNS("*", "name")
        assertEquals("A&B <\"Test\"> 'x'", names.item(0).textContent)  // Steuerzeichen entfernt
        assertEquals("Sparse & <dünn>", doc.getElementsByTagNameNS("*", "desc").item(0).textContent)
        assertFalse(xml.contains("\u0001"))
    }

    @Test fun descriptionFor_onlyWhenAllDetectionFixes() {
        assertNull(GpxExporter.descriptionFor(points))
        assertTrue(GpxExporter.descriptionFor(points.map { it.copy(detectionFix = true) }) != null)
        assertNull(GpxExporter.descriptionFor(emptyList()))
    }

    @Test fun exportFrom_writesGpx_skipsBrokenLastLine() {
        val dir = tmp.newFolder("2026-11-22_abc123")
        val lines = points.joinToString("") { TrackLogic.encodeLine(it) } + "{\"time\":\"2026-11-22T10:0"
        File(dir, TrackLogic.TRACK_FILE).writeText(lines)
        val n = GpxExporter.exportFrom(FileSessionFiles(dir), dir.name)
        assertEquals(2, n)
        val gpx = File(dir, GpxExporter.GPX_FILE)
        assertTrue(gpx.exists())
        assertEquals(2, parse(gpx.readText()).getElementsByTagNameNS("*", "trkpt").length)
    }

    @Test fun exportFrom_noTrack_noFile() {
        val dir = tmp.newFolder("s")
        assertEquals(0, GpxExporter.exportFrom(FileSessionFiles(dir), "s"))
        File(dir, TrackLogic.TRACK_FILE).writeText("kaputt\n")
        assertEquals(0, GpxExporter.exportFrom(FileSessionFiles(dir), "s"))
        assertFalse(File(dir, GpxExporter.GPX_FILE).exists())
    }
}
