package ch.etasystems.pirol.data.repository

import ch.etasystems.pirol.audio.export.ExcerptWav
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * T123: Liest beliebige PCM-WAV-Dateien (16-bit PCM oder 32-bit Float, Mono/Stereo/Mehrkanal,
 * beliebige Rate) und liefert Mono-16-bit. Reines JVM (unit-testbar).
 */
object WavParser {

    /** Mehrkanal-Interleaved -> Mono (Mittelwert). */
    fun downmixToMono(interleaved: ShortArray, channels: Int): ShortArray {
        if (channels <= 1) return interleaved
        val frames = interleaved.size / channels
        val out = ShortArray(frames)
        for (i in 0 until frames) {
            var sum = 0
            for (c in 0 until channels) sum += interleaved[i * channels + c]
            out[i] = (sum / channels).toShort()
        }
        return out
    }

    /** @return Mono-PCM oder null bei unbekanntem/fehlerhaftem Format */
    fun parse(bytes: ByteArray): ExcerptWav.Pcm? {
        if (bytes.size < 12) return null
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF" ||
            String(bytes, 8, 4, Charsets.US_ASCII) != "WAVE") return null
        var pos = 12
        var format = 0
        var channels = 0
        var rate = 0
        var bits = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = bb.getInt(pos + 4).toLong() and 0xFFFFFFFFL
            val body = pos + 8
            if (id == "fmt " && body + 16 <= bytes.size) {
                format = bb.getShort(body).toInt() and 0xFFFF
                channels = bb.getShort(body + 2).toInt()
                rate = bb.getInt(body + 4)
                bits = bb.getShort(body + 14).toInt()
                if (format == 0xFFFE && size >= 26 && body + 26 <= bytes.size) {
                    format = bb.getShort(body + 24).toInt() and 0xFFFF // WAVE_FORMAT_EXTENSIBLE: SubFormat
                }
            } else if (id == "data") {
                if (channels <= 0 || rate <= 0) return null
                // Nicht finalisierte Header (size 0 / zu gross): bis Dateiende lesen
                val avail = (bytes.size - body).toLong()
                val len = (if (size == 0L || size > avail) avail else size).toInt()
                val samples = when {
                    format == 1 && bits == 16 -> {
                        val n = len / 2
                        ShortArray(n).also { bb.position(body); bb.asShortBuffer().get(it, 0, n) }
                    }
                    format == 3 && bits == 32 -> {
                        val n = len / 4
                        ShortArray(n) { i ->
                            val f = bb.getFloat(body + i * 4).coerceIn(-1f, 1f)
                            (f * 32767f).toInt().toShort()
                        }
                    }
                    else -> return null
                }
                val mono = downmixToMono(samples, channels)
                return if (mono.isEmpty()) null else ExcerptWav.Pcm(mono, rate)
            }
            pos = body + size.toInt() + (size.toInt() and 1)
            if (size > Int.MAX_VALUE) return null
        }
        return null
    }
}
