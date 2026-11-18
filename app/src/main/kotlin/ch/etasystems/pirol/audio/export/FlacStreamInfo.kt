package ch.etasystems.pirol.audio.export

/**
 * T116: FLAC-Metadaten (reine Byte-Logik, unit-testbar).
 *
 * MediaCodec (`audio/flac`) liefert rohe FLAC-Frames (plus ggf. Codec-Config mit "fLaC"+STREAMINFO).
 * Fuer eine abspielbare .flac-Datei schreiben wir "fLaC" + STREAMINFO-Block + Frames.
 */
object FlacStreamInfo {

    const val STREAMINFO_SIZE = 34
    private val MAGIC = byteArrayOf(0x66, 0x4C, 0x61, 0x43)  // "fLaC"

    /**
     * STREAMINFO-Nutzdaten (34 Byte). Frame-Groessen 0 = unbekannt, MD5 = 0 (nicht berechnet) — beides erlaubt.
     */
    fun build(
        minBlock: Int, maxBlock: Int,
        sampleRate: Int, channels: Int, bitsPerSample: Int,
        totalSamples: Long
    ): ByteArray {
        val b = ByteArray(STREAMINFO_SIZE)
        b[0] = (minBlock shr 8).toByte(); b[1] = minBlock.toByte()
        b[2] = (maxBlock shr 8).toByte(); b[3] = maxBlock.toByte()
        b[10] = (sampleRate shr 12).toByte()
        b[11] = (sampleRate shr 4).toByte()
        b[12] = (((sampleRate and 0xF) shl 4) or ((channels - 1) shl 1) or ((bitsPerSample - 1) shr 4)).toByte()
        b[13] = ((((bitsPerSample - 1) and 0xF) shl 4) or ((totalSamples shr 32).toInt() and 0xF)).toByte()
        b[14] = (totalSamples shr 24).toByte()
        b[15] = (totalSamples shr 16).toByte()
        b[16] = (totalSamples shr 8).toByte()
        b[17] = totalSamples.toByte()
        return b
    }

    /** Setzt die Gesamt-Sample-Anzahl (36 bit) in einer vorhandenen STREAMINFO. */
    fun withTotalSamples(streamInfo: ByteArray, totalSamples: Long): ByteArray {
        val b = streamInfo.copyOf()
        b[13] = ((b[13].toInt() and 0xF0) or ((totalSamples shr 32).toInt() and 0xF)).toByte()
        b[14] = (totalSamples shr 24).toByte()
        b[15] = (totalSamples shr 16).toByte()
        b[16] = (totalSamples shr 8).toByte()
        b[17] = totalSamples.toByte()
        return b
    }

    /** Liest die Gesamt-Sample-Anzahl (Tests/Diagnose). */
    fun totalSamples(streamInfo: ByteArray): Long =
        ((streamInfo[13].toLong() and 0xF) shl 32) or
            ((streamInfo[14].toLong() and 0xFF) shl 24) or
            ((streamInfo[15].toLong() and 0xFF) shl 16) or
            ((streamInfo[16].toLong() and 0xFF) shl 8) or
            (streamInfo[17].toLong() and 0xFF)

    /**
     * Extrahiert die STREAMINFO (34 Byte) aus dem Codec-Config des Encoders, falls erkennbar:
     * "fLaC" + Block-Header (4 Byte) + STREAMINFO, oder Block-Header + STREAMINFO, oder nur STREAMINFO.
     */
    fun extractFromConfig(config: ByteArray): ByteArray? {
        val off = if (config.size >= 4 && config.copyOfRange(0, 4).contentEquals(MAGIC)) 4 else 0
        if (config.size - off >= 4 + STREAMINFO_SIZE && (config[off].toInt() and 0x7F) == 0) {
            val len = ((config[off + 1].toInt() and 0xFF) shl 16) or
                ((config[off + 2].toInt() and 0xFF) shl 8) or
                (config[off + 3].toInt() and 0xFF)
            if (len == STREAMINFO_SIZE) return config.copyOfRange(off + 4, off + 4 + STREAMINFO_SIZE)
        }
        if (config.size - off == STREAMINFO_SIZE) return config.copyOfRange(off, off + STREAMINFO_SIZE)
        return null
    }

    /** "fLaC" + letzter STREAMINFO-Block (Header 0x80 = last|Typ 0, Laenge 34). */
    fun fileHeader(streamInfo: ByteArray): ByteArray {
        val h = ByteArray(8 + STREAMINFO_SIZE)
        MAGIC.copyInto(h, 0)
        h[4] = 0x80.toByte()
        h[5] = 0; h[6] = 0; h[7] = STREAMINFO_SIZE.toByte()
        streamInfo.copyInto(h, 8)
        return h
    }

    /** true wenn die Bytes mit einem FLAC-Frame-Sync (14 bit 0x3FFE) beginnen. */
    fun isFrameStart(data: ByteArray): Boolean =
        data.size >= 2 && (data[0].toInt() and 0xFF) == 0xFF && (data[1].toInt() and 0xFC) == 0xF8
}
