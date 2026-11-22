package ch.etasystems.pirol.location

import java.io.DataInputStream
import java.io.InputStream

/**
 * Gemeindegrenzen (swisstopo swissBOUNDARIES3D, vereinfacht) im Speicher.
 * Format "PMUN" v1: siehe tools/build_municipalities.py.
 * Rein JVM, damit ohne Android testbar.
 */
class MunicipalityIndex private constructor(private val items: List<Municipality>) {

    class Municipality(
        val name: String,
        val code: String,
        val minE: Int, val minN: Int, val maxE: Int, val maxN: Int,
        /** Polygone; je Polygon Ringe (erster = Aussenring, Rest = Loecher), Koordinaten als E0,N0,E1,N1,... */
        val polygons: List<List<IntArray>>,
    ) {
        val label: String get() = "$name $code"
    }

    val size: Int get() = items.size

    /** Gemeinde als "Name KT" oder null, wenn der Punkt in keiner Gemeinde liegt (z.B. Ausland, grosser See). */
    fun find(e: Double, n: Double): String? {
        for (m in items) {
            if (e < m.minE || e > m.maxE || n < m.minN || n > m.maxN) continue
            if (m.polygons.any { contains(it, e, n) }) return m.label
        }
        // Vereinfachte Grenzen benachbarter Gemeinden koennen schmale Luecken lassen: naechste Kante im Umkreis
        var best: Municipality? = null
        var bestD = GAP_TOLERANCE_M * GAP_TOLERANCE_M
        for (m in items) {
            if (e < m.minE - GAP_TOLERANCE_M || e > m.maxE + GAP_TOLERANCE_M ||
                n < m.minN - GAP_TOLERANCE_M || n > m.maxN + GAP_TOLERANCE_M) continue
            for (poly in m.polygons) {
                val d = distSqToRing(poly[0], e, n)
                if (d < bestD) { bestD = d; best = m }
            }
        }
        return best?.label
    }

    companion object {
        /** Luecken-Toleranz in Metern (Vereinfachung 15 m). */
        const val GAP_TOLERANCE_M = 60.0

        /** Punkt-in-Polygon (Even-Odd ueber alle Ringe: Loecher zaehlen automatisch als "aussen"). */
        internal fun contains(rings: List<IntArray>, x: Double, y: Double): Boolean {
            var inside = false
            for (ring in rings) {
                val cnt = ring.size / 2
                var j = cnt - 1
                for (i in 0 until cnt) {
                    val xi = ring[2 * i].toDouble(); val yi = ring[2 * i + 1].toDouble()
                    val xj = ring[2 * j].toDouble(); val yj = ring[2 * j + 1].toDouble()
                    if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
                    j = i
                }
            }
            return inside
        }

        private fun distSqToRing(ring: IntArray, px: Double, py: Double): Double {
            val cnt = ring.size / 2
            var best = Double.MAX_VALUE
            var j = cnt - 1
            for (i in 0 until cnt) {
                val ax = ring[2 * j].toDouble(); val ay = ring[2 * j + 1].toDouble()
                val bx = ring[2 * i].toDouble(); val by = ring[2 * i + 1].toDouble()
                val dx = bx - ax; val dy = by - ay
                val l2 = dx * dx + dy * dy
                val t = if (l2 == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / l2).coerceIn(0.0, 1.0)
                val ex = px - (ax + t * dx); val ey = py - (ay + t * dy)
                best = minOf(best, ex * ex + ey * ey)
                j = i
            }
            return best
        }

        fun read(input: InputStream): MunicipalityIndex {
            val s = DataInputStream(input.buffered())
            val magic = ByteArray(4).also { s.readFully(it) }
            require(String(magic, Charsets.US_ASCII) == "PMUN") { "Kein PMUN-Format" }
            require(s.readUnsignedByte() == 1) { "Unbekannte PMUN-Version" }
            val count = varint(s)
            val list = ArrayList<Municipality>(count)
            repeat(count) {
                val nameBytes = ByteArray(s.readUnsignedByte()).also { s.readFully(it) }
                val codeBytes = ByteArray(2).also { s.readFully(it) }
                val minE = varint(s); val minN = varint(s)
                val maxE = minE + varint(s); val maxN = minN + varint(s)
                val polys = ArrayList<List<IntArray>>()
                repeat(varint(s)) {
                    val rings = ArrayList<IntArray>()
                    repeat(varint(s)) {
                        val pts = varint(s)
                        val coords = IntArray(pts * 2)
                        var x = 0; var y = 0
                        for (i in 0 until pts) {
                            x += zigzag(s); y += zigzag(s)
                            coords[2 * i] = x; coords[2 * i + 1] = y
                        }
                        rings.add(coords)
                    }
                    polys.add(rings)
                }
                list.add(Municipality(String(nameBytes, Charsets.UTF_8), String(codeBytes, Charsets.US_ASCII),
                    minE, minN, maxE, maxN, polys))
            }
            return MunicipalityIndex(list)
        }

        private fun varint(s: DataInputStream): Int {
            var r = 0L; var shift = 0
            while (true) {
                val b = s.readUnsignedByte()
                r = r or ((b and 0x7F).toLong() shl shift)
                if (b < 0x80) break
                shift += 7
            }
            return r.toInt()
        }

        private fun zigzag(s: DataInputStream): Int {
            val v = varint(s)
            return (v ushr 1) xor -(v and 1)
        }
    }
}
