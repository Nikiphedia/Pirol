package ch.etasystems.pirol.data.importer

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * T255: Kopf und Metadaten einer WAV-Datei, gelesen ohne die Audiodaten zu laden.
 * [dataSize] = -1, wenn unbekannt (nicht finalisierter Header): dann bis Dateiende lesen.
 */
data class WavInfo(
    val formatTag: Int,
    val channels: Int,
    val sampleRate: Int,
    val bitsPerSample: Int,
    val blockAlign: Int,
    val dataOffset: Long,
    val dataSize: Long,
    val guano: String? = null,
    /** LIST/INFO ICMT (AudioMoth schreibt hier Zeit und Zone) */
    val comment: String? = null,
    /** LIST/INFO ICRD */
    val creationDate: String? = null,
    val bextDate: String? = null,
    val bextTime: String? = null
) {
    /** PCM 8/16/24/32 bit oder Float 32/64 bit */
    val supported: Boolean
        get() = channels > 0 && sampleRate > 0 && blockAlign == channels * (bitsPerSample / 8) && when (formatTag) {
            FORMAT_PCM -> bitsPerSample in setOf(8, 16, 24, 32)
            FORMAT_FLOAT -> bitsPerSample == 32 || bitsPerSample == 64
            else -> false
        }

    /** Frames laut Header; null = unbekannt */
    val frames: Long? get() = if (dataSize >= 0 && blockAlign > 0) dataSize / blockAlign else null

    companion object {
        const val FORMAT_PCM = 1
        const val FORMAT_FLOAT = 3
        const val FORMAT_EXTENSIBLE = 0xFFFE
    }
}

/**
 * T255: WAV-Kopf streamend lesen (reines JVM). Grosse Chunks werden uebersprungen, Text-Chunks
 * (guan, LIST, bext) begrenzt gelesen. Mit [readAfterData] werden auch Chunks hinter den Daten gelesen
 * (GUANO steht bei manchen Geraeten am Ende); sonst steht der Stream danach am Anfang der Audiodaten.
 */
object WavInfoReader {

    private const val TEXT_CAP = 64 * 1024

    /** @param totalLength Dateilaenge in Bytes (-1 = unbekannt) */
    fun read(input: InputStream, totalLength: Long = -1L, readAfterData: Boolean = true): WavInfo? {
        val head = ByteArray(12)
        if (!readFullyOrFalse(input, head)) return null
        val riff = String(head, 0, 4, Charsets.US_ASCII)
        if (riff != "RIFF" || String(head, 8, 4, Charsets.US_ASCII) != "WAVE") return null
        var pos = 12L
        var fmt: ByteArray? = null
        var dataOffset = -1L
        var dataSize = -1L
        var guano: String? = null
        var comment: String? = null
        var creation: String? = null
        var bextDate: String? = null
        var bextTime: String? = null
        val ch = ByteArray(8)
        while (true) {
            if (!readFullyOrFalse(input, ch)) break
            pos += 8
            val id = String(ch, 0, 4, Charsets.US_ASCII)
            val size = ByteBuffer.wrap(ch, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
            val padded = size + (size and 1L)
            when (id) {
                "data" -> {
                    dataOffset = pos
                    val avail = if (totalLength > 0) totalLength - pos else -1L
                    dataSize = when {
                        size == 0L || size == 0xFFFFFFFFL -> avail      // nicht finalisiert
                        avail in 0 until size -> avail                   // abgeschnitten
                        else -> size
                    }
                    if (!readAfterData || dataSize < 0) break
                    skipFully(input, dataSize + (dataSize and 1L))
                    pos += dataSize + (dataSize and 1L)
                    continue
                }
                "fmt ", "guan", "LIST", "bext" -> {
                    val keep = minOf(size, TEXT_CAP.toLong()).toInt()
                    val body = ByteArray(keep)
                    if (!readFullyOrFalse(input, body)) break
                    skipFully(input, padded - keep)
                    when (id) {
                        "fmt " -> fmt = body
                        "guan" -> guano = text(body, 0, body.size)
                        "LIST" -> parseList(body)?.let { (c, d) -> comment = c ?: comment; creation = d ?: creation }
                        "bext" -> if (body.size >= 338) {
                            bextDate = text(body, 320, 10)
                            bextTime = text(body, 330, 8)
                        }
                    }
                }
                else -> skipFully(input, padded)
            }
            pos += padded
        }
        val f = fmt ?: return null
        if (f.size < 16 || dataOffset < 0) return null
        val bb = ByteBuffer.wrap(f).order(ByteOrder.LITTLE_ENDIAN)
        var tag = bb.getShort(0).toInt() and 0xFFFF
        if (tag == WavInfo.FORMAT_EXTENSIBLE && f.size >= 26) tag = bb.getShort(24).toInt() and 0xFFFF
        return WavInfo(
            formatTag = tag,
            channels = bb.getShort(2).toInt() and 0xFFFF,
            sampleRate = bb.getInt(4),
            bitsPerSample = bb.getShort(14).toInt() and 0xFFFF,
            blockAlign = bb.getShort(12).toInt() and 0xFFFF,
            dataOffset = dataOffset,
            dataSize = dataSize,
            guano = guano,
            comment = comment,
            creationDate = creation,
            bextDate = bextDate,
            bextTime = bextTime
        )
    }

    /** LIST/INFO: (ICMT, ICRD) */
    private fun parseList(body: ByteArray): Pair<String?, String?>? {
        if (body.size < 4 || String(body, 0, 4, Charsets.US_ASCII) != "INFO") return null
        var p = 4
        var icmt: String? = null
        var icrd: String? = null
        val bb = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
        while (p + 8 <= body.size) {
            val id = String(body, p, 4, Charsets.US_ASCII)
            val size = bb.getInt(p + 4)
            if (size < 0) break
            val start = p + 8
            val len = minOf(size, body.size - start)
            when (id) {
                "ICMT" -> icmt = text(body, start, len)
                "ICRD" -> icrd = text(body, start, len)
            }
            p = start + size + (size and 1)
        }
        return icmt to icrd
    }

    private fun text(b: ByteArray, off: Int, len: Int): String =
        String(b, off, maxOf(0, minOf(len, b.size - off)), Charsets.UTF_8).trimEnd('\u0000', ' ', '\n', '\r')

    private fun readFullyOrFalse(input: InputStream, dst: ByteArray): Boolean {
        var off = 0
        while (off < dst.size) {
            val r = input.read(dst, off, dst.size - off)
            if (r < 0) return false
            off += r
        }
        return true
    }

    /** Ueberspringt genau [n] Bytes (oder bis Dateiende). */
    internal fun skipFully(input: InputStream, n: Long) {
        var left = n
        while (left > 0) {
            val s = input.skip(left)
            if (s > 0) { left -= s; continue }
            if (input.read() < 0) return
            left--
        }
    }
}

/**
 * T255: Liest die Audiodaten einer WAV blockweise und mischt auf Mono (Mittelwert der Kanaele), 16 bit.
 * Der Stream muss am Datenanfang stehen ([WavInfoReader.read] mit readAfterData = false). Reines JVM.
 */
class WavPcmReader(private val info: WavInfo, private val input: InputStream) {

    private val bytesPerSample = info.bitsPerSample / 8
    private var remaining: Long = if (info.dataSize >= 0) info.dataSize else Long.MAX_VALUE
    private var buffer = ByteArray(0)

    init { require(info.supported) { "WAV-Format nicht unterstuetzt" } }

    /** Naechster Block mit hoechstens [maxFrames] Mono-Samples; null = Ende. */
    fun read(maxFrames: Int): ShortArray? {
        val wantBytes = minOf(maxFrames.toLong() * info.blockAlign, remaining).toInt()
        if (wantBytes <= 0) return null
        if (buffer.size < wantBytes) buffer = ByteArray(wantBytes)
        var got = 0
        while (got < wantBytes) {
            val r = input.read(buffer, got, wantBytes - got)
            if (r < 0) break
            got += r
        }
        val frames = got / info.blockAlign
        if (frames <= 0) { remaining = 0; return null }
        remaining -= frames.toLong() * info.blockAlign
        if (got < wantBytes) remaining = 0  // Dateiende
        val ch = info.channels
        val out = ShortArray(frames)
        var p = 0
        for (i in 0 until frames) {
            var sum = 0.0
            for (c in 0 until ch) {
                sum += sampleAt(p)
                p += bytesPerSample
            }
            val v = Math.round(sum / ch)
            out[i] = v.coerceIn(Short.MIN_VALUE.toLong(), Short.MAX_VALUE.toLong()).toInt().toShort()
        }
        return out
    }

    /** Ein Sample auf 16-bit-Skala */
    private fun sampleAt(p: Int): Double {
        val b = buffer
        return when (info.formatTag) {
            WavInfo.FORMAT_FLOAT -> {
                val f = if (bytesPerSample == 4) {
                    java.lang.Float.intBitsToFloat(le32(b, p)).toDouble()
                } else {
                    java.lang.Double.longBitsToDouble((le32(b, p).toLong() and 0xFFFFFFFFL) or (le32(b, p + 4).toLong() shl 32))
                }
                f.coerceIn(-1.0, 1.0) * 32767.0
            }
            else -> when (bytesPerSample) {
                1 -> (((b[p].toInt() and 0xFF) - 128) shl 8).toDouble()
                2 -> ((b[p].toInt() and 0xFF) or (b[p + 1].toInt() shl 8)).toShort().toDouble()
                3 -> (((b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8) or (b[p + 2].toInt() shl 16)) / 256.0)
                else -> le32(b, p) / 65536.0
            }
        }
    }

    private fun le32(b: ByteArray, p: Int): Int =
        (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8) or
            ((b[p + 2].toInt() and 0xFF) shl 16) or (b[p + 3].toInt() shl 24)

    companion object {
        /** Kann [info] gelesen werden? (sonst Rueckfall auf MediaCodec) */
        fun canRead(info: WavInfo?): Boolean = info?.supported == true
    }
}
