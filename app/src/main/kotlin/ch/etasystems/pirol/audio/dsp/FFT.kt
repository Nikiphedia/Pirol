package ch.etasystems.pirol.audio.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Radix-2 Cooley-Tukey FFT-Implementierung.
 * Reines Kotlin, keine externen Dependencies (KMP-faehig).
 *
 * Performance-Ziel: 2048-Punkt FFT < 1ms auf Mittelklasse-Geraet.
 */
object FFT {

    /**
     * In-place FFT (Decimation-in-Time).
     * real und imag muessen gleiche Laenge haben, Laenge muss Zweierpotenz sein.
     *
     * Nach dem Aufruf enthalten real/imag die komplexen Frequenz-Koeffizienten.
     */
    fun fft(real: FloatArray, imag: FloatArray) {
        val n = real.size
        require(n > 0 && (n and (n - 1)) == 0) {
            "FFT-Laenge muss Zweierpotenz sein, ist aber $n"
        }
        require(real.size == imag.size) {
            "real (${real.size}) und imag (${imag.size}) muessen gleiche Laenge haben"
        }

        // Bit-Reversal Permutation
        bitReverse(real, imag, n)

        // Butterfly-Operationen
        var halfSize = 1
        while (halfSize < n) {
            val stepSize = halfSize * 2
            val angleStep = -PI / halfSize // -2*PI / stepSize

            // Twiddle-Faktoren fuer diese Stufe
            var k = 0
            while (k < n) {
                var angle = 0.0
                for (j in 0 until halfSize) {
                    val twiddleReal = cos(angle).toFloat()
                    val twiddleImag = sin(angle).toFloat()

                    val evenIdx = k + j
                    val oddIdx = k + j + halfSize

                    // Butterfly
                    val tReal = twiddleReal * real[oddIdx] - twiddleImag * imag[oddIdx]
                    val tImag = twiddleReal * imag[oddIdx] + twiddleImag * real[oddIdx]

                    real[oddIdx] = real[evenIdx] - tReal
                    imag[oddIdx] = imag[evenIdx] - tImag
                    real[evenIdx] = real[evenIdx] + tReal
                    imag[evenIdx] = imag[evenIdx] + tImag

                    angle += angleStep
                }
                k += stepSize
            }
            halfSize = stepSize
        }
    }

    /**
     * Berechnet das Power-Spektrum aus FFT-Koeffizienten.
     * Gibt FloatArray(N/2 + 1) zurueck mit real[i]^2 + imag[i]^2.
     *
     * Nur die positive Haelfte wird zurueckgegeben (Nyquist inklusive).
     */
    fun powerSpectrum(real: FloatArray, imag: FloatArray): FloatArray {
        val n = real.size
        val numBins = n / 2 + 1
        val power = FloatArray(numBins)
        for (i in 0 until numBins) {
            power[i] = real[i] * real[i] + imag[i] * imag[i]
        }
        return power
    }

    /**
     * Berechnet das Power-Spektrum in einen vorhandenen Buffer (zero-alloc).
     * output muss mindestens N/2+1 gross sein.
     */
    fun powerSpectrumInto(real: FloatArray, imag: FloatArray, output: FloatArray) {
        val numBins = real.size / 2 + 1
        for (i in 0 until numBins) {
            output[i] = real[i] * real[i] + imag[i] * imag[i]
        }
    }

    /**
     * Bit-Reversal Permutation in-place.
     */
    private fun bitReverse(real: FloatArray, imag: FloatArray, n: Int) {
        var j = 0
        for (i in 0 until n - 1) {
            if (i < j) {
                // Swap real
                val tempR = real[j]
                real[j] = real[i]
                real[i] = tempR
                // Swap imag
                val tempI = imag[j]
                imag[j] = imag[i]
                imag[i] = tempI
            }
            var k = n shr 1
            while (k <= j) {
                j -= k
                k = k shr 1
            }
            j += k
        }
    }
}
