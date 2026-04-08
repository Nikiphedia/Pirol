package ch.etasystems.pirol.audio.dsp

import kotlin.math.floor

/**
 * Linearer Interpolations-Resampler.
 * Portiert von AMSEL Desktop — genuegt fuer BirdNET-Input-Qualitaet.
 *
 * Unterstuetzte Konvertierungen:
 * - 48 kHz → 32 kHz (Standard: Oboe-Aufnahme → BirdNET)
 * - 96 kHz → 32 kHz (Fledermaus-Modus → BirdNET)
 * - Beliebige Raten (lineares Resampling)
 */
object AudioResampler {

    /**
     * Resampelt Float-Audio auf die Ziel-Samplerate.
     * Lineare Interpolation zwischen benachbarten Samples.
     *
     * @param input Normalisierte Samples [-1.0, 1.0]
     * @param fromRate Quell-Samplerate in Hz
     * @param toRate Ziel-Samplerate in Hz
     * @return Resampeltes FloatArray
     */
    fun resample(input: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        if (fromRate == toRate) return input.copyOf()
        require(fromRate > 0 && toRate > 0) { "Sampleraten muessen positiv sein" }
        if (input.isEmpty()) return FloatArray(0)

        val ratio = fromRate.toDouble() / toRate.toDouble()
        val outputLength = (input.size / ratio).toInt()
        val output = FloatArray(outputLength)

        for (i in 0 until outputLength) {
            val srcPos = i * ratio
            val srcIdx = floor(srcPos).toInt()
            val frac = (srcPos - srcIdx).toFloat()

            output[i] = if (srcIdx + 1 < input.size) {
                input[srcIdx] * (1f - frac) + input[srcIdx + 1] * frac
            } else {
                input[srcIdx.coerceAtMost(input.size - 1)]
            }
        }

        return output
    }

    /**
     * Convenience-Overload: ShortArray → Float-Normalisierung + Resampling.
     * Kombiniert die Konvertierung von 16-bit PCM zu normalisierten Floats
     * mit dem Resampling in einem Schritt.
     *
     * @param input 16-bit PCM Samples
     * @param fromRate Quell-Samplerate in Hz
     * @param toRate Ziel-Samplerate in Hz
     * @return Resampeltes FloatArray, normalisiert auf [-1.0, 1.0]
     */
    fun resample(input: ShortArray, fromRate: Int, toRate: Int): FloatArray {
        // ShortArray → Float normalisieren
        val floats = FloatArray(input.size) { input[it] / 32768.0f }
        return resample(floats, fromRate, toRate)
    }
}
