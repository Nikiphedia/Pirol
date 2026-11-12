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

    // requestedSampleRate_ fuer onErrorAfterClose()-Neustart merken (>0 sicherstellen)
    requestedSampleRate_ = (sampleRate > 0) ? sampleRate : 48000;

    // F1: setSampleRate(0) → Oboe waehlt native Geraeterate (kein erzwungener Resampler)
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Input)
           ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
           ->setSharingMode(oboe::SharingMode::Exclusive)
           ->setSampleRate(0)                         // F1: auto-select native rate
           ->setChannelCount(oboe::ChannelCount::Mono)
           ->setFormat(oboe::AudioFormat::I16)
           ->setInputPreset(oboe::InputPreset::Unprocessed)
           ->setDataCallback(this)
           ->setErrorCallback(this);

    // F2: AAudio versuchen, bei Fehler OpenSL ES Fallback
    const char* apiName = "AAudio";
    oboe::Result result = builder.openStream(stream_);
    if (result != oboe::Result::OK) {
        LOGE("AAudio fehlgeschlagen: %s — versuche OpenSL ES", oboe::convertToText(result));
        builder.setAudioApi(oboe::AudioApi::OpenSLES);
        result = builder.openStream(stream_);
        if (result != oboe::Result::OK) {
            LOGE("OpenSL ES fehlgeschlagen: %s", oboe::convertToText(result));
            stream_.reset();
            return false;
        }
        apiName = "OpenSL ES";
    }

    // F1: Tatsaechliche Rate NACH Open lesen → Ring-Buffer mit korrekter Rate dimensionieren
    int32_t actual = stream_->getSampleRate();
    actualSampleRate_.store(actual, std::memory_order_release);
    int32_t bufferSize = actual * bufferDurationSeconds_;
    ringBuffer_.resize(bufferSize);

    // AC3: Log mit gewaeuhlter Rate + API (fuer Fairphone-Diagnose)
    LOGI("Audio-Stream: %d Hz, API: %s, Buffer: %d s (%d Samples)",
         actual, apiName, bufferDurationSeconds_, bufferSize);

    result = stream_->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("requestStart fehlgeschlagen: %s", oboe::convertToText(result));
        stream_->close();
        stream_.reset();
        return false;
    }

    isRecording_.store(true, std::memory_order_release);
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
