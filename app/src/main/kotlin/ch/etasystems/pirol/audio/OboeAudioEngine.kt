package ch.etasystems.pirol.audio

/**
 * Kotlin-Wrapper fuer die native Oboe Audio-Engine (C++/NDK).
 *
 * Low-Latency Aufnahme mit konfigurierbarer Sample-Rate und Ring-Buffer (Preroll).
 * Zwei Betriebsmodi:
 * - Voegel: 48 kHz, 16-bit Mono (Standard)
 * - Fledermaus: 96 kHz+ fuer Ultraschall bis ~120 kHz
 */
class OboeAudioEngine {

    companion object {
        const val SAMPLE_RATE_BIRDS = 48000
        const val SAMPLE_RATE_BATS = 96000
        const val DEFAULT_PREROLL_SECONDS = 30

        init {
            System.loadLibrary("pirol_audio")
        }
    }

    // --- Native Methoden (JNI) ---
    private external fun nativeStartRecording(sampleRate: Int): Boolean
    private external fun nativeStopRecording()
    private external fun nativeIsRecording(): Boolean
    private external fun nativeGetAudioChunk(numSamples: Int): ShortArray?
    private external fun nativeGetActualSampleRate(): Int
    private external fun nativeSetBufferDurationSeconds(seconds: Int)

    // --- Oeffentliches API ---

    /**
     * Aufnahme starten.
     * @param sampleRate Gewuenschte Sample-Rate (Default: 48000 Hz fuer Voegel)
     * @return true wenn erfolgreich gestartet
     */
    fun start(sampleRate: Int = SAMPLE_RATE_BIRDS): Boolean {
        return nativeStartRecording(sampleRate)
    }

    /** Aufnahme stoppen. */
    fun stop() {
        nativeStopRecording()
    }

    /** Laeuft die Aufnahme gerade? */
    val isRecording: Boolean
        get() = nativeIsRecording()

    /** Tatsaechlich vom Geraet gewaehrte Sample-Rate (kann von angeforderter abweichen). */
    val actualSampleRate: Int
        get() = nativeGetActualSampleRate()

    /**
     * Die juengsten Samples als Chunk lesen.
     * @param durationMs Gewuenschte Dauer in Millisekunden
     * @return ShortArray mit den Samples, oder leeres Array falls nichts verfuegbar
     */
    fun getLatestChunk(durationMs: Int): ShortArray {
        val rate = actualSampleRate
        if (rate <= 0) return ShortArray(0)

        val numSamples = (rate.toLong() * durationMs / 1000).toInt()
        if (numSamples <= 0) return ShortArray(0)

        return nativeGetAudioChunk(numSamples) ?: ShortArray(0)
    }

    /**
     * Ring-Buffer Preroll-Dauer setzen (in Sekunden).
     * Muss VOR start() aufgerufen werden.
     * @param seconds Preroll-Dauer (Default: 30, max: 300)
     */
    fun setPrerollDuration(seconds: Int = DEFAULT_PREROLL_SECONDS) {
        nativeSetBufferDurationSeconds(seconds)
    }
}
