package ch.etasystems.pirol.ml

/** 3-Sekunden Audio-Block mit Float-Samples fuer die Embedding-Pipeline */
data class AudioBlock(
    val samples: FloatArray,
    val sampleRate: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioBlock) return false
        return sampleRate == other.sampleRate && samples.contentEquals(other.samples)
    }

    override fun hashCode(): Int = 31 * samples.contentHashCode() + sampleRate
}
