package ch.etasystems.pirol.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

class MunicipalityTest {

    @Test
    fun lv95_referenzpunkt_bern() {
        val p = Lv95.fromWgs84(46.95108, 7.43863)
        assertEquals(2_600_000.0, p.e, 2.0)
        assertEquals(1_200_000.0, p.n, 2.0)
    }

    private fun ring(vararg xy: Int) = xy

    @Test
    fun pointInPolygon_mitLoch() {
        val outer = ring(0, 0, 100, 0, 100, 100, 0, 100)
        val hole = ring(40, 40, 60, 40, 60, 60, 40, 60)
        val rings = listOf(outer, hole)
        assertTrue(MunicipalityIndex.contains(rings, 10.0, 10.0))
        assertFalse(MunicipalityIndex.contains(rings, 50.0, 50.0))
        assertFalse(MunicipalityIndex.contains(rings, 150.0, 50.0))
    }

    // Minimaler Schreiber nach tools/build_municipalities.py
    private fun varint(v: Long, o: ByteArrayOutputStream) {
        var x = v
        while (x >= 0x80) { o.write(((x and 0x7F) or 0x80).toInt()); x = x shr 7 }
        o.write(x.toInt())
    }
    private fun zz(v: Int, o: ByteArrayOutputStream) = varint(((v shl 1) xor (v shr 31)).toLong() and 0xFFFFFFFFL, o)

    private fun sample(): ByteArray {
        val o = ByteArrayOutputStream()
        o.write("PMUN".toByteArray()); o.write(1)
        varint(1, o)
        val name = "Köniz".toByteArray()
        o.write(name.size); o.write(name); o.write("BE".toByteArray())
        varint(2_000_000, o); varint(1_000_000, o); varint(100, o); varint(100, o)
        varint(1, o)          // 1 Polygon
        varint(2, o)          // Aussenring + Loch
        for (r in listOf(
            listOf(2_000_000 to 1_000_000, 2_000_100 to 1_000_000, 2_000_100 to 1_000_100, 2_000_000 to 1_000_100),
            listOf(2_000_040 to 1_000_040, 2_000_060 to 1_000_040, 2_000_060 to 1_000_060, 2_000_040 to 1_000_060),
        )) {
            varint(r.size.toLong(), o)
            var px = 0; var py = 0
            for ((x, y) in r) { zz(x - px, o); zz(y - py, o); px = x; py = y }
        }
        return o.toByteArray()
    }

    @Test
    fun assetLeser_kleinesBeispiel() {
        val idx = MunicipalityIndex.read(sample().inputStream())
        assertEquals(1, idx.size)
        assertEquals("Köniz BE", idx.find(2_000_010.0, 1_000_010.0))
        assertNull(idx.find(2_000_500.0, 1_000_050.0))
    }

    @Test
    fun echtesAsset_bernUndZuerich() {
        val f = File("src/main/assets/regions/ch_municipalities.bin")
        assertTrue("Asset fehlt: ${f.absolutePath}", f.exists())
        val idx = f.inputStream().use { MunicipalityIndex.read(it) }
        fun at(lat: Double, lon: Double) = Lv95.fromWgs84(lat, lon).let { idx.find(it.e, it.n) }
        assertEquals("Bern BE", at(46.948, 7.447))
        assertEquals("Zürich ZH", at(47.3769, 8.5417))
        assertEquals("Köniz BE", at(46.9242, 7.4125))
        assertNull(at(48.8566, 2.3522)) // Paris
    }
}
