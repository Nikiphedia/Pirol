package ch.etasystems.pirol.location

/** Umrechnung WGS84 -> Schweizer Landeskoordinaten LV95 (swisstopo-Naeherungsformel, Genauigkeit < 1 m). */
object Lv95 {
    /** Ostwert (E) und Nordwert (N) in Metern. */
    data class Point(val e: Double, val n: Double)

    fun fromWgs84(latDeg: Double, lonDeg: Double): Point {
        val phi = (latDeg * 3600.0 - 169028.66) / 10000.0
        val lam = (lonDeg * 3600.0 - 26782.5) / 10000.0
        val e = 2600072.37 +
            211455.93 * lam -
            10938.51 * lam * phi -
            0.36 * lam * phi * phi -
            44.54 * lam * lam * lam
        val n = 1200147.07 +
            308807.95 * phi +
            3745.25 * lam * lam +
            76.63 * phi * phi -
            194.56 * lam * lam * phi +
            119.79 * phi * phi * phi
        return Point(e, n)
    }
}
