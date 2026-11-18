package ch.etasystems.pirol.audio

import kotlin.math.abs
import kotlin.math.pow

/**
 * T122: Wiedergabe-Normalisierung (nur Abspielen, die Aufnahme bleibt unveraendert).
 * Skaliert 16-bit-PCM (little endian) so, dass die Spitze bei [targetDbfs] liegt,
 * hoechstens um [maxGainDb] verstaerkt (sonst wird bei Stille nur Rauschen laut).
 */
object PlaybackGain {

    /** Verstaerkungsfaktor fuer eine gemessene Spitze (0..32767). */
    fun gainFor(peak: Int, targetDbfs: Float, maxGainDb: Float): Float {
        if (peak <= 0) return 1f
        val target = 32767f * 10f.pow(targetDbfs / 20f)
        val maxGain = 10f.pow(maxGainDb / 20f)
        return (target / peak).coerceAtMost(maxGain)
    }

    /** Normalisiert [data] in place; liefert den angewendeten Faktor. */
    fun normalizeInPlace(data: ByteArray, targetDbfs: Float = -10f, maxGainDb: Float = 30f): Float {
        var peak = 0
        var i = 0
        while (i + 1 < data.size) {
            val s = (data[i].toInt() and 0xFF) or (data[i + 1].toInt() shl 8)
            val a = abs(s.toShort().toInt())
            if (a > peak) peak = a
            i += 2
        }
        val gain = gainFor(peak, targetDbfs, maxGainDb)
        if (gain == 1f) return gain
        i = 0
        while (i + 1 < data.size) {
            val s = ((data[i].toInt() and 0xFF) or (data[i + 1].toInt() shl 8)).toShort()
            val v = (s * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            data[i] = (v and 0xFF).toByte()
            data[i + 1] = ((v shr 8) and 0xFF).toByte()
            i += 2
        }
        return gain
    }
}
