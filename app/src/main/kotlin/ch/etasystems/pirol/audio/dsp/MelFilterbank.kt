package ch.etasystems.pirol.audio.dsp

import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * Mel-Filterbank: wandelt lineares Power-Spektrum in Mel-skalierte Energiewerte um.
 *
 * Verwendet HTK-Mel-Skala: mel = 2595 * log10(1 + freq/700)
 * Dreieckfilter werden im Konstruktor vorberechnet fuer zero-alloc im Hot-Path.
 *
 * @param sampleRate Audio-Samplerate in Hz
 * @param fftSize FFT-Fenstergroesse (bestimmt Frequenzaufloesung)
 * @param numMelBins Anzahl Mel-Baender
 * @param fMin Untere Grenzfrequenz in Hz
 * @param fMax Obere Grenzfrequenz in Hz
 */
class MelFilterbank(
    sampleRate: Int,
    fftSize: Int,
    numMelBins: Int,
    fMin: Float,
    fMax: Float
) {
    // Vorberechnete Filterbank: sparse Darstellung pro Mel-Bin
    // Jeder Filter hat Start-Index, End-Index und Gewichte
    private val filterStart: IntArray       // Erster FFT-Bin-Index pro Mel-Bin
    private val filterLength: IntArray      // Anzahl FFT-Bins pro Mel-Bin
    private val filterWeights: Array<FloatArray> // Gewichte pro Mel-Bin

    // Wiederverwendbarer Ausgabe-Buffer
    private val outputBuffer = FloatArray(numMelBins)

    init {
        val numFftBins = fftSize / 2 + 1
        val melMin = hzToMel(fMin)
        val melMax = hzToMel(fMax)

        // numMelBins + 2 gleichmaessig verteilte Punkte auf Mel-Skala
        val melPoints = FloatArray(numMelBins + 2) { i ->
            melMin + i * (melMax - melMin) / (numMelBins + 1)
        }

        // Mel-Punkte zurueck in Hz und dann in FFT-Bin-Indizes
        val binIndices = IntArray(numMelBins + 2) { i ->
            val hz = melToHz(melPoints[i])
            val bin = floor((fftSize + 1).toFloat() * hz / sampleRate).toInt()
            bin.coerceIn(0, numFftBins - 1)
        }

        // Dreieckfilter aufbauen (sparse)
        filterStart = IntArray(numMelBins)
        filterLength = IntArray(numMelBins)
        filterWeights = Array(numMelBins) { m ->
            val left = binIndices[m]
            val center = binIndices[m + 1]
            val right = binIndices[m + 2]

            filterStart[m] = left
            val len = (right - left + 1).coerceAtLeast(0)
            filterLength[m] = len

            FloatArray(len) { k ->
                val bin = left + k
                when {
                    bin < left -> 0f
                    bin <= center -> {
                        if (center == left) 1f
                        else (bin - left).toFloat() / (center - left).toFloat()
                    }
                    bin <= right -> {
                        if (right == center) 1f
                        else (right - bin).toFloat() / (right - center).toFloat()
                    }
                    else -> 0f
                }
            }
        }
    }

    /**
     * Wendet die Mel-Filterbank auf ein Power-Spektrum an.
     * Gibt FloatArray(numMelBins) mit linearen Energiewerten zurueck.
     *
     * ACHTUNG: Der zurueckgegebene Buffer wird intern wiederverwendet!
     * Werte muessen vor dem naechsten apply()-Aufruf kopiert oder verarbeitet werden.
     */
    fun apply(powerSpectrum: FloatArray): FloatArray {
        for (m in outputBuffer.indices) {
            var energy = 0f
            val start = filterStart[m]
            val weights = filterWeights[m]
            for (k in weights.indices) {
                energy += powerSpectrum[start + k] * weights[k]
            }
            outputBuffer[m] = energy
        }
        return outputBuffer
    }

    companion object {
        /** HTK Mel-Skala: Hz → Mel */
        fun hzToMel(hz: Float): Float = 2595f * log10(1f + hz / 700f)

        /** HTK Mel-Skala: Mel → Hz */
        fun melToHz(mel: Float): Float = 700f * (10f.pow(mel / 2595f) - 1f)
    }
}
