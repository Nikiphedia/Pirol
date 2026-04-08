#pragma once

#include <oboe/Oboe.h>
#include <atomic>
#include "RingBuffer.h"

/**
 * Low-Latency Audio-Engine basierend auf Google Oboe.
 *
 * - Konfigurierbare Sample-Rate (48 kHz Standard, bis 384 kHz fuer Ultraschall)
 * - 16-bit Mono (int16_t)
 * - Ring-Buffer fuer Preroll (Default 30s)
 * - Thread-safe: Callback auf Realtime-Thread, Lesen von JNI-Thread
 */
class AudioEngine : public oboe::AudioStreamCallback {
public:
    AudioEngine();
    ~AudioEngine();

    /**
     * Aufnahme starten mit gewuenschter Sample-Rate.
     * @param sampleRate Gewuenschte Rate (48000, 96000, etc.)
     * @return true wenn Stream erfolgreich geoeffnet und gestartet
     */
    bool start(int32_t sampleRate);

    /** Aufnahme stoppen und Stream schliessen. */
    void stop();

    /** Laeuft die Aufnahme gerade? */
    bool isRecording() const;

    /**
     * Die juengsten numSamples aus dem Ring-Buffer kopieren.
     * @param numSamples Anzahl gewuenschte Samples
     * @param outBuffer Ziel-Array (muss numSamples gross sein)
     * @return Tatsaechlich kopierte Samples
     */
    int32_t getLatestSamples(int32_t numSamples, int16_t* outBuffer);

    /** Tatsaechlich vom Geraet gewaehrte Sample-Rate. */
    int32_t getActualSampleRate() const;

    /**
     * Ring-Buffer-Dauer setzen (in Sekunden). Vor start() aufrufen!
     * Default: 30 Sekunden.
     */
    void setBufferDurationSeconds(int32_t seconds);

    // --- Oboe Callback ---
    oboe::DataCallbackResult onAudioReady(
        oboe::AudioStream* stream,
        void* audioData,
        int32_t numFrames) override;

    void onErrorAfterClose(
        oboe::AudioStream* stream,
        oboe::Result error) override;

private:
    std::shared_ptr<oboe::AudioStream> stream_;
    RingBuffer ringBuffer_;
    std::atomic<bool> isRecording_;
    std::atomic<int32_t> actualSampleRate_;
    int32_t bufferDurationSeconds_;

    // Fuer automatischen Neustart bei Fehler (z.B. Geraetewechsel)
    int32_t requestedSampleRate_;
};
