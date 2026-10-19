package ch.etasystems.pirol.audio.dsp

import java.util.concurrent.ConcurrentHashMap
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

        // Twiddle-Faktoren aus Cache (einmal pro FFT-Groesse berechnet).
        // Vorher: cos()/sin() pro Butterfly → bei N=2048 ~11'000 Trig-Aufrufe pro Frame.
        val tw = twiddles(n)
        val twCos = tw.cos
        val twSin = tw.sin

        // Butterfly-Operationen
        var halfSize = 1
        while (halfSize < n) {
            val stepSize = halfSize * 2
            // Twiddle fuer Index j in dieser Stufe: exp(-i*PI*j/halfSize) = Tabelle[j * (n / stepSize)]
            val tableStride = n / stepSize

            var k = 0
            while (k < n) {
                var tableIdx = 0
                for (j in 0 until halfSize) {
                    val twiddleReal = twCos[tableIdx]
                    val twiddleImag = twSin[tableIdx]

                    val evenIdx = k + j
                    val oddIdx = k + j + halfSize

                    // Butterfly
                    val oddReal = real[oddIdx]
                    val oddImag = imag[oddIdx]
                    val tReal = twiddleReal * oddReal - twiddleImag * oddImag
                    val tImag = twiddleReal * oddImag + twiddleImag * oddReal

                    val evenReal = real[evenIdx]
                    val evenImag = imag[evenIdx]
                    real[oddIdx] = evenReal - tReal
                    imag[oddIdx] = evenImag - tImag
                    real[evenIdx] = evenReal + tReal
                    imag[evenIdx] = evenImag + tImag

                    tableIdx += tableStride
                }
                k += stepSize
            }
            halfSize = stepSize
        }
    }

    /** Vorberechnete Twiddle-Faktoren exp(-2*PI*i*j/n) fuer j in [0, n/2). */
    private class Twiddles(val cos: FloatArray, val sin: FloatArray)

    private val twiddleCache = ConcurrentHashMap<Int, Twiddles>()

    private fun twiddles(n: Int): Twiddles = twiddleCache.getOrPut(n) {
        val half = n / 2
        val c = FloatArray(half)
        val s = FloatArray(half)
        for (j in 0 until half) {
            val angle = -2.0 * PI * j / n
            c[j] = cos(angle).toFloat()
            s[j] = sin(angle).toFloat()
        }
        Twiddles(c, s)
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
