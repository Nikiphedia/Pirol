package ch.etasystems.pirol.ml

import ch.etasystems.pirol.audio.dsp.FFT
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * MFCC-Feature-Extractor fuer Vogelgesang-Embedding.
 * Portiert von AMSEL Desktop (ch.etasystems.amsel.core.similarity.MfccExtractor),
 * angepasst fuer Android: Nur bird()-Preset, nutzt bestehende FFT-Klasse.
 *
 * Pipeline: Audio → Frames (Hann Window) → FFT → Power Spectrum → Mel-Filter → Log → DCT → MFCC
 */
class MfccExtractor {

    companion object {
        // Bird-Preset Parameter (aus AMSEL)
        private const val SAMPLE_RATE = 48000
        private const val N_FFT = 2048
        private const val HOP_SIZE = 512
        private const val N_MELS = 64
        private const val F_MIN = 40.0f
        private const val F_MAX = 15000.0f
        private const val N_MFCC = 13

        // Delta-Berechnung: Lineare Regression mit width=2
        private const val DELTA_WIDTH = 2

        // Kleine Konstante um log(0) zu vermeiden
        private const val LOG_FLOOR = 1e-10f
    }

    // Vorberechnete Mel-Filterbank (lazy, da sie fuer gegebene Parameter immer gleich ist)
    private val melFilterbank: Array<FloatArray> by lazy { buildMelFilterbank() }

    // Vorberechnete Hann-Window (N_FFT Samples)
    private val hannWindow: FloatArray by lazy { buildHannWindow(N_FFT) }

    // Vorberechnete DCT-Matrix [N_MFCC][N_MELS]
    private val dctMatrix: Array<FloatArray> by lazy { buildDctMatrix() }

    /**
     * Extrahiert MFCC-Frames aus Audio-Samples.
     *
     * @param samples Audio-Daten, normalisiert [-1, 1]
     * @param sampleRate Samplerate der Input-Daten (wird auf 48kHz resampelt falls noetig)
     * @return Array[nFrames][N_MFCC] mit MFCC-Koeffizienten pro Frame
     */
    fun extract(samples: FloatArray, sampleRate: Int): Array<FloatArray> {
        val audio = ensureSampleRate(samples, sampleRate)
        if (audio.size < N_FFT) return emptyArray()

        val numFrames = (audio.size - N_FFT) / HOP_SIZE + 1
        val frames = Array(numFrames) { FloatArray(N_MFCC) }

        // Wiederverwendbare Buffer fuer FFT (zero-alloc pro Frame)
        val fftReal = FloatArray(N_FFT)
        val fftImag = FloatArray(N_FFT)
        val powerBins = FloatArray(N_FFT / 2 + 1)

        for (frameIdx in 0 until numFrames) {
            val offset = frameIdx * HOP_SIZE

            // Frame extrahieren + Hann-Fenster anwenden
            for (i in 0 until N_FFT) {
                fftReal[i] = if (offset + i < audio.size) {
                    audio[offset + i] * hannWindow[i]
                } else {
                    0f
                }
                fftImag[i] = 0f
            }

            // FFT ausfuehren (in-place)
            FFT.fft(fftReal, fftImag)

            // Power-Spektrum berechnen
            FFT.powerSpectrumInto(fftReal, fftImag, powerBins)

            // Mel-Filterbank anwenden → Log → DCT → MFCC
            val melEnergies = applyMelFilterbank(powerBins)
            applyLogAndDct(melEnergies, frames[frameIdx])
        }

        return frames
    }

    /**
     * Extrahiert eine 26-dimensionale Zusammenfassung: 13 Mean + 13 Stddev ueber alle Frames.
     *
     * @param samples Audio-Daten, normalisiert [-1, 1]
     * @param sampleRate Samplerate der Input-Daten
     * @return FloatArray(26) — [mean_0..mean_12, std_0..std_12]
     */
    fun extractSummary(samples: FloatArray, sampleRate: Int): FloatArray {
        val frames = extract(samples, sampleRate)
        if (frames.isEmpty()) return FloatArray(N_MFCC * 2)

        val summary = FloatArray(N_MFCC * 2)
        val numFrames = frames.size

        // Mean berechnen
        for (frame in frames) {
            for (c in 0 until N_MFCC) {
                summary[c] += frame[c]
            }
        }
        for (c in 0 until N_MFCC) {
            summary[c] /= numFrames
        }

        // Standardabweichung berechnen
        for (frame in frames) {
            for (c in 0 until N_MFCC) {
                val diff = frame[c] - summary[c]
                summary[N_MFCC + c] += diff * diff
            }
        }
        for (c in 0 until N_MFCC) {
            summary[N_MFCC + c] = sqrt(summary[N_MFCC + c] / numFrames)
        }

        return summary
    }

    /**
     * Extrahiert einen 43-dimensionalen Enhanced-Embedding-Vektor:
     * - 13 MFCC (Mean ueber Frames)
     * - 13 Delta-MFCC (Mean)
     * - 13 Delta-Delta-MFCC (Mean)
     * - 4 Spectral Features (Centroid, Bandwidth, ZCR, Flatness)
     * L2-normalisiert.
     *
     * @param samples Audio-Daten, normalisiert [-1, 1]
     * @param sampleRate Samplerate der Input-Daten
     * @return FloatArray(43) — L2-normalisierter Embedding-Vektor
     */
    fun extractEnhanced(samples: FloatArray, sampleRate: Int): FloatArray {
        val audio = ensureSampleRate(samples, sampleRate)
        val frames = extract(audio, SAMPLE_RATE)
        if (frames.isEmpty()) return FloatArray(43)

        // Delta und Delta-Delta berechnen
        val deltas = computeDeltas(frames)
        val deltaDeltas = computeDeltas(deltas)

        val numFrames = frames.size
        val embedding = FloatArray(43)

        // 13 MFCC Mean
        for (frame in frames) {
            for (c in 0 until N_MFCC) {
                embedding[c] += frame[c]
            }
        }
        for (c in 0 until N_MFCC) {
            embedding[c] /= numFrames
        }

        // 13 Delta Mean
        for (frame in deltas) {
            for (c in 0 until N_MFCC) {
                embedding[N_MFCC + c] += frame[c]
            }
        }
        for (c in 0 until N_MFCC) {
            embedding[N_MFCC + c] /= numFrames
        }

        // 13 Delta-Delta Mean
        for (frame in deltaDeltas) {
            for (c in 0 until N_MFCC) {
                embedding[2 * N_MFCC + c] += frame[c]
            }
        }
        for (c in 0 until N_MFCC) {
            embedding[2 * N_MFCC + c] /= numFrames
        }

        // 4 Spectral Features
        val spectral = computeSpectralFeatures(audio)
        embedding[39] = spectral[0] // Spectral Centroid
        embedding[40] = spectral[1] // Spectral Bandwidth
        embedding[41] = spectral[2] // Zero Crossing Rate
        embedding[42] = spectral[3] // Spectral Flatness

        // L2-Normalisierung
        l2Normalize(embedding)

        return embedding
    }

    // --- Private Hilfsfunktionen ---

    /**
     * Stellt sicher, dass Audio bei SAMPLE_RATE (48kHz) vorliegt.
     * Einfaches Resampling per linearer Interpolation.
     */
    private fun ensureSampleRate(samples: FloatArray, sampleRate: Int): FloatArray {
        if (sampleRate == SAMPLE_RATE) return samples
        if (samples.isEmpty()) return samples

        val ratio = sampleRate.toDouble() / SAMPLE_RATE.toDouble()
        val outputLength = (samples.size / ratio).toInt()
        val output = FloatArray(outputLength)

        for (i in 0 until outputLength) {
            val srcPos = i * ratio
            val srcIdx = srcPos.toInt()
            val frac = (srcPos - srcIdx).toFloat()

            output[i] = if (srcIdx + 1 < samples.size) {
                samples[srcIdx] * (1f - frac) + samples[srcIdx + 1] * frac
            } else {
                samples[srcIdx.coerceAtMost(samples.size - 1)]
            }
        }

        return output
    }

    /**
     * Mel-Filterbank auf Power-Spektrum anwenden.
     * Gibt FloatArray(N_MELS) mit Mel-Band-Energien zurueck.
     */
    private fun applyMelFilterbank(powerSpectrum: FloatArray): FloatArray {
        val melEnergies = FloatArray(N_MELS)
        for (m in 0 until N_MELS) {
            var sum = 0f
            val filter = melFilterbank[m]
            for (k in filter.indices) {
                sum += filter[k] * powerSpectrum[k]
            }
            melEnergies[m] = sum
        }
        return melEnergies
    }

    /**
     * Log-Kompression + DCT auf Mel-Energien anwenden → MFCC-Koeffizienten.
     */
    private fun applyLogAndDct(melEnergies: FloatArray, output: FloatArray) {
        // Log-Kompression
        for (m in melEnergies.indices) {
            melEnergies[m] = ln((melEnergies[m] + LOG_FLOOR).toDouble()).toFloat()
        }

        // DCT: output[c] = sum_m(dctMatrix[c][m] * logMel[m])
        for (c in 0 until N_MFCC) {
            var sum = 0f
            val dctRow = dctMatrix[c]
            for (m in 0 until N_MELS) {
                sum += dctRow[m] * melEnergies[m]
            }
            output[c] = sum
        }
    }

    /**
     * Delta-Koeffizienten per linearer Regression mit width=DELTA_WIDTH.
     * delta[t][c] = (sum_{n=1}^{W} n*(frame[t+n][c] - frame[t-n][c])) / (2 * sum_{n=1}^{W} n^2)
     */
    private fun computeDeltas(frames: Array<FloatArray>): Array<FloatArray> {
        val numFrames = frames.size
        val numCoeffs = frames[0].size
        val deltas = Array(numFrames) { FloatArray(numCoeffs) }

        // Normalisierungsfaktor: 2 * sum(n^2) fuer n=1..DELTA_WIDTH
        var denom = 0f
        for (n in 1..DELTA_WIDTH) {
            denom += (n * n).toFloat()
        }
        denom *= 2f

        for (t in 0 until numFrames) {
            for (c in 0 until numCoeffs) {
                var sum = 0f
                for (n in 1..DELTA_WIDTH) {
                    val tPlus = (t + n).coerceAtMost(numFrames - 1)
                    val tMinus = (t - n).coerceAtLeast(0)
                    sum += n * (frames[tPlus][c] - frames[tMinus][c])
                }
                deltas[t][c] = sum / denom
            }
        }

        return deltas
    }

    /**
     * Berechnet 4 Spectral Features ueber das gesamte Audio-Signal:
     * [0] Spectral Centroid (normalisiert auf 0..1 bzgl. Nyquist)
     * [1] Spectral Bandwidth (normalisiert)
     * [2] Zero Crossing Rate (normalisiert)
     * [3] Spectral Flatness (0..1, 1 = weisses Rauschen)
     */
    private fun computeSpectralFeatures(audio: FloatArray): FloatArray {
        val features = FloatArray(4)
        if (audio.size < N_FFT) return features

        // Spectral Centroid + Bandwidth: Mittlerer Frame
        val midOffset = (audio.size - N_FFT) / 2
        val fftReal = FloatArray(N_FFT)
        val fftImag = FloatArray(N_FFT)
        for (i in 0 until N_FFT) {
            fftReal[i] = audio[midOffset + i] * hannWindow[i]
            fftImag[i] = 0f
        }
        FFT.fft(fftReal, fftImag)
        val power = FFT.powerSpectrum(fftReal, fftImag)

        // Spectral Centroid: gewichteter Mittelwert der Frequenzbins
        var totalPower = 0f
        var weightedSum = 0f
        val nyquist = SAMPLE_RATE / 2f
        for (k in power.indices) {
            val freq = k.toFloat() * SAMPLE_RATE / N_FFT
            weightedSum += freq * power[k]
            totalPower += power[k]
        }
        val centroid = if (totalPower > 0f) weightedSum / totalPower else 0f
        features[0] = centroid / nyquist // Normalisiert auf 0..1

        // Spectral Bandwidth: gewichtete Standardabweichung um den Centroid
        var bandwidthSum = 0f
        for (k in power.indices) {
            val freq = k.toFloat() * SAMPLE_RATE / N_FFT
            val diff = freq - centroid
            bandwidthSum += diff * diff * power[k]
        }
        val bandwidth = if (totalPower > 0f) sqrt(bandwidthSum / totalPower) else 0f
        features[1] = bandwidth / nyquist // Normalisiert auf 0..1

        // Zero Crossing Rate: Anteil der Vorzeichenwechsel
        var zeroCrossings = 0
        for (i in 1 until audio.size) {
            if ((audio[i] >= 0f && audio[i - 1] < 0f) || (audio[i] < 0f && audio[i - 1] >= 0f)) {
                zeroCrossings++
            }
        }
        features[2] = zeroCrossings.toFloat() / audio.size.toFloat()

        // Spectral Flatness: geometrischer Mittelwert / arithmetischer Mittelwert der Power
        // Berechnung im Log-Raum fuer numerische Stabilitaet
        var logSum = 0f
        var arithmSum = 0f
        var validBins = 0
        for (k in 1 until power.size) { // Index 0 (DC) ueberspringen
            if (power[k] > 0f) {
                logSum += ln(power[k].toDouble()).toFloat()
                arithmSum += power[k]
                validBins++
            }
        }
        features[3] = if (validBins > 0 && arithmSum > 0f) {
            val geoMean = Math.exp((logSum / validBins).toDouble()).toFloat()
            val arithMean = arithmSum / validBins
            (geoMean / arithMean).coerceIn(0f, 1f)
        } else {
            0f
        }

        return features
    }

    /**
     * Baut die Mel-Filterbank: Array[N_MELS][N_FFT/2+1] mit Dreiecksfiltern.
     * Mel-Skala: mel = 2595 * log10(1 + f/700)
     */
    private fun buildMelFilterbank(): Array<FloatArray> {
        val numBins = N_FFT / 2 + 1
        val melMin = hzToMel(F_MIN)
        val melMax = hzToMel(F_MAX)

        // N_MELS + 2 gleichmaessig verteilte Punkte auf der Mel-Skala
        val melPoints = FloatArray(N_MELS + 2) { i ->
            melMin + i * (melMax - melMin) / (N_MELS + 1)
        }

        // Mel-Punkte zurueck in Hz und dann in FFT-Bin-Indizes
        val binIndices = IntArray(N_MELS + 2) { i ->
            val hz = melToHz(melPoints[i])
            ((hz * N_FFT / SAMPLE_RATE) + 0.5f).toInt().coerceIn(0, numBins - 1)
        }

        // Dreiecksfilter erstellen
        val filterbank = Array(N_MELS) { FloatArray(numBins) }

        for (m in 0 until N_MELS) {
            val left = binIndices[m]
            val center = binIndices[m + 1]
            val right = binIndices[m + 2]

            // Ansteigende Flanke: left → center
            if (center > left) {
                for (k in left..center) {
                    filterbank[m][k] = (k - left).toFloat() / (center - left).toFloat()
                }
            }

            // Abfallende Flanke: center → right
            if (right > center) {
                for (k in center..right) {
                    filterbank[m][k] = (right - k).toFloat() / (right - center).toFloat()
                }
            }
        }

        return filterbank
    }

    /**
     * Baut die DCT-II-Matrix [N_MFCC][N_MELS].
     * DCT-II: C[k][n] = sqrt(2/N) * cos(PI * k * (2n+1) / (2N))
     */
    private fun buildDctMatrix(): Array<FloatArray> {
        val scale = sqrt(2.0 / N_MELS).toFloat()
        return Array(N_MFCC) { k ->
            FloatArray(N_MELS) { n ->
                scale * cos(PI * k * (2 * n + 1) / (2.0 * N_MELS)).toFloat()
            }
        }
    }

    /**
     * Hann-Fenster erstellen: w[n] = 0.5 * (1 - cos(2*PI*n / (N-1)))
     */
    private fun buildHannWindow(size: Int): FloatArray {
        return FloatArray(size) { n ->
            (0.5 * (1.0 - cos(2.0 * PI * n / (size - 1)))).toFloat()
        }
    }

    /** Hz → Mel: mel = 2595 * log10(1 + f/700) */
    private fun hzToMel(hz: Float): Float {
        return 2595f * log10(1f + hz / 700f)
    }

    /** Mel → Hz: f = 700 * (10^(mel/2595) - 1) */
    private fun melToHz(mel: Float): Float {
        return 700f * (10f.pow(mel / 2595f) - 1f)
    }

    /** L2-Normalisierung in-place */
    private fun l2Normalize(vector: FloatArray) {
        var sumSq = 0f
        for (v in vector) {
            sumSq += v * v
        }
        val norm = sqrt(sumSq)
        if (norm > 1e-10f) {
            for (i in vector.indices) {
                vector[i] /= norm
            }
        }
    }
}
