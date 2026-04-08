#include "AudioEngine.h"
#include <android/log.h>

#define LOG_TAG "PirolAudio"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

AudioEngine::AudioEngine()
    : isRecording_(false)
    , actualSampleRate_(0)
    , bufferDurationSeconds_(30)
    , requestedSampleRate_(48000) {
}

AudioEngine::~AudioEngine() {
    stop();
}

bool AudioEngine::start(int32_t sampleRate) {
    if (isRecording_.load(std::memory_order_acquire)) {
        LOGI("Aufnahme laeuft bereits, stoppe zuerst");
        stop();
    }

    requestedSampleRate_ = sampleRate;

    // Ring-Buffer dimensionieren: sampleRate * bufferDurationSeconds
    int32_t bufferSize = sampleRate * bufferDurationSeconds_;
    ringBuffer_.resize(bufferSize);

    LOGI("Starte Audio-Stream: %d Hz, Buffer %d s (%d Samples)",
         sampleRate, bufferDurationSeconds_, bufferSize);

    // Oboe Stream konfigurieren
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Input)
           ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
           ->setSharingMode(oboe::SharingMode::Exclusive)
           ->setSampleRate(sampleRate)
           ->setChannelCount(oboe::ChannelCount::Mono)
           ->setFormat(oboe::AudioFormat::I16)
           ->setInputPreset(oboe::InputPreset::Unprocessed)
           ->setDataCallback(this)
           ->setErrorCallback(this);

    oboe::Result result = builder.openStream(stream_);
    if (result != oboe::Result::OK) {
        LOGE("Fehler beim Oeffnen des Streams: %s", oboe::convertToText(result));
        stream_.reset();
        return false;
    }

    // Tatsaechliche Sample-Rate vom Geraet
    int32_t actual = stream_->getSampleRate();
    actualSampleRate_.store(actual, std::memory_order_release);

    if (actual != sampleRate) {
        LOGI("Geraet liefert %d Hz statt angeforderte %d Hz", actual, sampleRate);
        // Ring-Buffer mit tatsaechlicher Rate neu dimensionieren
        int32_t correctedSize = actual * bufferDurationSeconds_;
        ringBuffer_.resize(correctedSize);
    }

    result = stream_->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("Fehler beim Starten des Streams: %s", oboe::convertToText(result));
        stream_->close();
        stream_.reset();
        return false;
    }

    isRecording_.store(true, std::memory_order_release);
    LOGI("Audio-Stream gestartet: %d Hz (angefordert: %d Hz)", actual, sampleRate);
    return true;
}

void AudioEngine::stop() {
    if (!isRecording_.load(std::memory_order_acquire)) return;

    isRecording_.store(false, std::memory_order_release);

    if (stream_) {
        stream_->requestStop();
        stream_->close();
        stream_.reset();
        LOGI("Audio-Stream gestoppt");
    }
}

bool AudioEngine::isRecording() const {
    return isRecording_.load(std::memory_order_acquire);
}

int32_t AudioEngine::getLatestSamples(int32_t numSamples, int16_t* outBuffer) {
    return ringBuffer_.readLatest(outBuffer, numSamples);
}

int32_t AudioEngine::getActualSampleRate() const {
    return actualSampleRate_.load(std::memory_order_acquire);
}

void AudioEngine::setBufferDurationSeconds(int32_t seconds) {
    if (seconds < 1) seconds = 1;
    if (seconds > 300) seconds = 300; // Max 5 Minuten
    bufferDurationSeconds_ = seconds;
    LOGI("Buffer-Dauer gesetzt: %d Sekunden", seconds);
}

// --- Oboe Callbacks ---

oboe::DataCallbackResult AudioEngine::onAudioReady(
        oboe::AudioStream* /*stream*/,
        void* audioData,
        int32_t numFrames) {
    // KEIN new/malloc hier — Realtime-Thread!
    auto* samples = static_cast<const int16_t*>(audioData);
    ringBuffer_.write(samples, numFrames);
    return oboe::DataCallbackResult::Continue;
}

void AudioEngine::onErrorAfterClose(
        oboe::AudioStream* /*stream*/,
        oboe::Result error) {
    LOGE("Stream-Fehler: %s — versuche Neustart", oboe::convertToText(error));

    // Automatischer Neustart bei Fehler (z.B. USB-Mikrofon abgezogen/angesteckt)
    if (isRecording_.load(std::memory_order_acquire)) {
        isRecording_.store(false, std::memory_order_release);
        stream_.reset();
        start(requestedSampleRate_);
    }
}
