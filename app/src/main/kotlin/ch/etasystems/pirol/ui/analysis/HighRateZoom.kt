package ch.etasystems.pirol.ui.analysis

import ch.etasystems.pirol.audio.dsp.MelSpectrogram
import ch.etasystems.pirol.audio.dsp.SpectrogramConfig

/**
 * T294: Zoom-Ausschnitt einer Ultraschall-Session ohne den ganzen Ausschnitt als PCM im Speicher.
 *
 * Bis 96 kHz bleibt der Zoom wie bisher (ganzer Ausschnitt als PCM, Spektrum/Oszillogramm moeglich).
 * Darueber wird ein langer Ausschnitt (mehr als [MAX_PCM_SAMPLES]) blockweise gelesen und nur jeder
 * Stride-te Frame gerechnet, wie die Session-Uebersicht. Spektrum und Oszillogramm brauchen dann einen
 * kuerzeren Ausschnitt.
 */
object HighRateZoom {
    /** Groesster Ausschnitt als PCM bei Ultraschall: 8 Mio. Samples = 16 MB (ca. 21 s bei 384 kHz) */
    const val MAX_PCM_SAMPLES = 8_000_000L

    /** Hoechstzahl Frames der gestreamten Zoom-Ansicht (wie die Session-Uebersicht) */
    const val TARGET_FRAMES = 2048

    /** Gelesene Samples je Block */
    const val BLOCK_SAMPLES = 480_000

    /** Muss der Ausschnitt gestreamt werden? Nur bei Ultraschall-Raten und langen Ausschnitten. */
    fun needsStreaming(sampleRate: Int, durationSec: Float): Boolean =
        SpectrogramConfig.isUltrasoundRate(sampleRate) && durationSec.toDouble() * sampleRate > MAX_PCM_SAMPLES

    /** Stride, damit hoechstens [targetFrames] Frames den ganzen Ausschnitt abdecken. */
    fun strideFor(frames: Long, hopSize: Int, targetFrames: Int = TARGET_FRAMES): Int {
        val total = (frames / hopSize).coerceAtLeast(1L)
        return ((total + targetFrames - 1) / targetFrames).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
    }

    /**
     * Mel-Frames fuer die Session-Frames [startFrame, endFrame) mit Stride, blockweise ueber [read]
     * (liefert die Samples [from, to) oder null bei Fehler/Ende). Haelt nie mehr als einen Block.
     */
    fun stridedFrames(
        sampleRate: Int,
        startFrame: Long,
        endFrame: Long,
        read: (from: Long, to: Long) -> ShortArray?,
        isActive: () -> Boolean = { true }
    ): List<FloatArray> {
        val config = SpectrogramConfig.forSessionRate(sampleRate)
        val stride = strideFor(endFrame - startFrame, config.hopSize)
        val mel = MelSpectrogram(sampleRate, config)
        val out = ArrayList<FloatArray>()
        var pos = startFrame
        while (pos < endFrame && isActive()) {
            val to = minOf(endFrame, pos + BLOCK_SAMPLES)
            val block = read(pos, to) ?: break
            if (block.isEmpty()) break
            out.addAll(mel.process(block) { it % stride == 0L })
            pos += block.size
        }
        return if (out.size > TARGET_FRAMES) out.subList(0, TARGET_FRAMES) else out
    }
}
