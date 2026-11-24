package ch.etasystems.pirol.audio.export

import ch.etasystems.pirol.audio.dsp.SpeechBandMasker
import java.io.InputStream

/**
 * T261: Liest eine PIROL-WAV (44-Byte-Header, 16-bit Mono) und gibt sie mit ausgeblendetem Sprachband in den
 * Abschnitten des [masker] aus. Header und alle Bytes ausserhalb der Abschnitte bleiben bitgenau, auch ein
 * ungerades Restbyte am Ende. Blockweise (nie die ganze Datei im Speicher).
 *
 * @param source Original-Datei ab Byte 0 (wird beim Schliessen mitgeschlossen)
 * @param startFrame Session-Frame des ersten Daten-Samples dieser Datei (Segment-Start)
 */
class MaskedWavStream(
    private val source: InputStream,
    private val startFrame: Long,
    private val masker: SpeechBandMasker,
    private val headerSize: Int = ExcerptWav.HEADER_SIZE
) : InputStream() {

    private companion object { const val BLOCK_FRAMES = 32_768 }

    private val buf = ByteArray(BLOCK_FRAMES * 2)
    private val shorts = ShortArray(BLOCK_FRAMES)
    private var bufLen = 0
    private var bufPos = 0
    private var headerLeft = headerSize
    private var frame = startFrame
    private var eof = false

    override fun read(): Int {
        val b = ByteArray(1)
        return if (read(b, 0, 1) <= 0) -1 else (b[0].toInt() and 0xFF)
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (bufPos >= bufLen && !fill()) return -1
        val n = minOf(len, bufLen - bufPos)
        System.arraycopy(buf, bufPos, b, off, n)
        bufPos += n
        return n
    }

    /** Naechsten Block laden (Header unveraendert, Daten ggf. bearbeitet); false = Dateiende. */
    private fun fill(): Boolean {
        if (eof) return false
        bufPos = 0
        if (headerLeft > 0) {
            val r = source.read(buf, 0, headerLeft)
            if (r <= 0) { eof = true; bufLen = 0; return false }
            headerLeft -= r
            bufLen = r
            return true
        }
        var got = 0
        while (got < buf.size) {
            val r = source.read(buf, got, buf.size - got)
            if (r <= 0) { eof = true; break }
            got += r
        }
        if (got == 0) { bufLen = 0; return false }
        val frames = got / 2
        if (frames > 0 && masker.affects(frame, frames)) {
            for (i in 0 until frames) {
                shorts[i] = ((buf[2 * i].toInt() and 0xFF) or (buf[2 * i + 1].toInt() shl 8)).toShort()
            }
            masker.process(frame, shorts, frames)
            for (i in 0 until frames) {
                val v = shorts[i].toInt()
                buf[2 * i] = (v and 0xFF).toByte()
                buf[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
            }
        }
        frame += frames
        bufLen = got
        return true
    }

    override fun close() = source.close()
}
