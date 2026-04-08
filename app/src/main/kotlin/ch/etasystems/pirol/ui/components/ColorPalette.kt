package ch.etasystems.pirol.ui.components

import androidx.compose.ui.graphics.Color

/**
 * Vorberechnete Farbpaletten fuer Sonogramm-Darstellung.
 *
 * Jede Palette ist ein IntArray(256) mit ARGB-gepackten Farbwerten.
 * Input: normalisierter dB-Wert 0.0f (leise/minDb) bis 1.0f (laut/0dB).
 * Output: Compose Color.
 */
enum class SpectrogramPalette {
    MAGMA,      // Schwarz → dunkelrot → gelb → weiss
    VIRIDIS,    // Dunkelviolett → blau → gruen → gelb
    GRAYSCALE;  // Schwarz → weiss

    companion object {
        private const val LUT_SIZE = 256

        // Magma-Stuetzstellen (matplotlib approximiert, 7 Punkte)
        private val MAGMA_STOPS = floatArrayOf(0.0f, 0.15f, 0.30f, 0.50f, 0.70f, 0.85f, 1.0f)
        private val MAGMA_R = intArrayOf(0, 20, 100, 183, 240, 254, 252)
        private val MAGMA_G = intArrayOf(0, 5, 15, 55, 130, 210, 253)
        private val MAGMA_B = intArrayOf(4, 60, 110, 121, 40, 75, 191)

        // Viridis-Stuetzstellen (matplotlib approximiert, 7 Punkte)
        private val VIRIDIS_STOPS = floatArrayOf(0.0f, 0.15f, 0.30f, 0.50f, 0.70f, 0.85f, 1.0f)
        private val VIRIDIS_R = intArrayOf(68, 59, 33, 32, 94, 187, 253)
        private val VIRIDIS_G = intArrayOf(1, 28, 95, 144, 201, 223, 231)
        private val VIRIDIS_B = intArrayOf(84, 140, 154, 141, 98, 39, 37)

        // LUTs werden einmalig berechnet (lazy)
        val magmaLut: IntArray by lazy { buildLut(MAGMA_STOPS, MAGMA_R, MAGMA_G, MAGMA_B) }
        val viridisLut: IntArray by lazy { buildLut(VIRIDIS_STOPS, VIRIDIS_R, VIRIDIS_G, VIRIDIS_B) }
        val grayscaleLut: IntArray by lazy { buildGrayscaleLut() }

        /**
         * Baut eine 256-Eintraege ARGB-LUT aus Stuetzstellen mit linearer Interpolation.
         */
        private fun buildLut(
            stops: FloatArray,
            rValues: IntArray,
            gValues: IntArray,
            bValues: IntArray
        ): IntArray {
            val lut = IntArray(LUT_SIZE)
            for (i in 0 until LUT_SIZE) {
                val t = i / (LUT_SIZE - 1).toFloat()

                // Finde das passende Segment
                var segIdx = 0
                for (s in 0 until stops.size - 1) {
                    if (t >= stops[s]) segIdx = s
                }

                val t0 = stops[segIdx]
                val t1 = stops[segIdx + 1]
                val frac = if (t1 > t0) (t - t0) / (t1 - t0) else 0f

                val r = lerp(rValues[segIdx], rValues[segIdx + 1], frac)
                val g = lerp(gValues[segIdx], gValues[segIdx + 1], frac)
                val b = lerp(bValues[segIdx], bValues[segIdx + 1], frac)

                lut[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            return lut
        }

        private fun buildGrayscaleLut(): IntArray {
            val lut = IntArray(LUT_SIZE)
            for (i in 0 until LUT_SIZE) {
                lut[i] = (0xFF shl 24) or (i shl 16) or (i shl 8) or i
            }
            return lut
        }

        private fun lerp(a: Int, b: Int, fraction: Float): Int {
            return (a + (b - a) * fraction).toInt().coerceIn(0, 255)
        }

        /**
         * Gibt die vorberechnete LUT fuer die gewaehlte Palette zurueck.
         */
        fun lutFor(palette: SpectrogramPalette): IntArray = when (palette) {
            MAGMA -> magmaLut
            VIRIDIS -> viridisLut
            GRAYSCALE -> grayscaleLut
        }
    }
}

/**
 * Mapped einen normalisierten dB-Wert (0.0 = leise, 1.0 = laut) auf eine Compose Color.
 */
fun colorForValue(normalizedValue: Float, palette: SpectrogramPalette): Color {
    val lut = SpectrogramPalette.lutFor(palette)
    val idx = (normalizedValue.coerceIn(0f, 1f) * 255).toInt().coerceIn(0, 255)
    return Color(lut[idx])
}
