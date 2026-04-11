package ch.etasystems.pirol.ml

import ch.etasystems.pirol.audio.AudioChunk

/**
 * Akkumulierter Audio-Block aus mehreren Chunks.
 * Wird freigegeben wenn die Zieldauer (3s) erreicht ist.
 */
data class AccumulatedBlock(
    val samples: ShortArray,   // Alle Samples konkateniert
    val sampleRate: Int,
    val timestampMs: Long,     // Timestamp des ersten Chunks im Block
    val durationMs: Int        // Tatsaechliche Dauer
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AccumulatedBlock) return false
        return samples.contentEquals(other.samples) &&
                sampleRate == other.sampleRate &&
                timestampMs == other.timestampMs &&
                durationMs == other.durationMs
    }

    override fun hashCode(): Int {
        var result = samples.contentHashCode()
        result = 31 * result + sampleRate
        result = 31 * result + timestampMs.hashCode()
        result = 31 * result + durationMs
        return result
    }
}

/**
 * Sammelt AudioChunks bis ein 3-Sekunden-Fenster voll ist.
 *
 * Feed-Mechanismus: Jeder Chunk wird angehaengt, sobald genug Samples fuer
 * die Zieldauer vorhanden sind, wird ein AccumulatedBlock freigegeben und
 * der interne Buffer zurueckgesetzt.
 *
 * @param targetDurationMs Zieldauer pro Block in Millisekunden (Standard: 3000)
 */
class ChunkAccumulator(
    private val targetDurationMs: Int = CHUNK_DURATION_MS.toInt()
) {
    // Interne Sample-Sammlung
    private val sampleBuffer = mutableListOf<ShortArray>()
    private var totalSamples = 0
    private var currentSampleRate: Int? = null
    private var firstTimestampMs: Long = 0L

    /**
     * Fuegt einen Chunk hinzu und gibt einen fertigen Block zurueck
     * wenn die Zieldauer erreicht ist.
     *
     * @throws IllegalArgumentException wenn die Samplerate sich innerhalb eines Blocks aendert
     */
    fun feed(chunk: AudioChunk): AccumulatedBlock? {
        // Samplerate-Konsistenz pruefen
        val rate = currentSampleRate
        if (rate != null && rate != chunk.sampleRate) {
            throw IllegalArgumentException(
                "Inkonsistente Samplerate: erwartet $rate, erhalten ${chunk.sampleRate}"
            )
        }

        // Erster Chunk im Block — Samplerate und Timestamp merken
        if (currentSampleRate == null) {
            currentSampleRate = chunk.sampleRate
            firstTimestampMs = chunk.timestampMs
        }

        sampleBuffer.add(chunk.samples)
        totalSamples += chunk.samples.size

        // Pruefen ob Zieldauer erreicht
        val targetSamples = chunk.sampleRate.toLong() * targetDurationMs / 1000
        if (totalSamples >= targetSamples) {
            return buildBlock()
        }

        return null
    }

    /** Internen State zuruecksetzen */
    fun reset() {
        sampleBuffer.clear()
        totalSamples = 0
        currentSampleRate = null
        firstTimestampMs = 0L
    }

    /** Konkateniert alle gesammelten Samples und gibt einen Block zurueck */
    private fun buildBlock(): AccumulatedBlock {
        val sr = currentSampleRate ?: throw IllegalStateException("Keine Samplerate gesetzt")

        // Alle ShortArrays in ein zusammenhaengendes Array kopieren
        val combined = ShortArray(totalSamples)
        var offset = 0
        for (buf in sampleBuffer) {
            buf.copyInto(combined, destinationOffset = offset)
            offset += buf.size
        }

        val durationMs = (totalSamples.toLong() * 1000 / sr).toInt()
        val block = AccumulatedBlock(
            samples = combined,
            sampleRate = sr,
            timestampMs = firstTimestampMs,
            durationMs = durationMs
        )

        // Buffer zuruecksetzen fuer naechsten Block
        reset()
        return block
    }
}
