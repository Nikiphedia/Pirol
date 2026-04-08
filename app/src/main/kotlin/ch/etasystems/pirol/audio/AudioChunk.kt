package ch.etasystems.pirol.audio

/**
 * Ein Audio-Chunk aus dem Ring-Buffer.
 * Wird alle 100ms vom RecordingService emittiert (~4800 Samples @48kHz).
 *
 * @param samples PCM-Daten als Kopie (nicht der interne Buffer)
 * @param sampleRate Tatsaechliche Sample-Rate des Geraets
 * @param timestampMs System-Zeitstempel der Emission (SystemClock.elapsedRealtime)
 */
data class AudioChunk(
    val samples: ShortArray,
    val sampleRate: Int,
    val timestampMs: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioChunk) return false
        return samples.contentEquals(other.samples) &&
                sampleRate == other.sampleRate &&
                timestampMs == other.timestampMs
    }

    override fun hashCode(): Int {
        var result = samples.contentHashCode()
        result = 31 * result + sampleRate
        result = 31 * result + timestampMs.hashCode()
        return result
    }
}
