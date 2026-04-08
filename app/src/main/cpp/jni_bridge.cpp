#include <jni.h>
#include "AudioEngine.h"

// Globale Engine-Instanz — lebt fuer die gesamte App-Laufzeit
static AudioEngine gEngine;

extern "C" {

JNIEXPORT jboolean JNICALL
Java_ch_etasystems_pirol_audio_OboeAudioEngine_nativeStartRecording(
        JNIEnv* /*env*/, jobject /*thiz*/, jint sampleRate) {
    return static_cast<jboolean>(gEngine.start(sampleRate));
}

JNIEXPORT void JNICALL
Java_ch_etasystems_pirol_audio_OboeAudioEngine_nativeStopRecording(
        JNIEnv* /*env*/, jobject /*thiz*/) {
    gEngine.stop();
}

JNIEXPORT jboolean JNICALL
Java_ch_etasystems_pirol_audio_OboeAudioEngine_nativeIsRecording(
        JNIEnv* /*env*/, jobject /*thiz*/) {
    return static_cast<jboolean>(gEngine.isRecording());
}

JNIEXPORT jshortArray JNICALL
Java_ch_etasystems_pirol_audio_OboeAudioEngine_nativeGetAudioChunk(
        JNIEnv* env, jobject /*thiz*/, jint numSamples) {
    if (numSamples <= 0) return nullptr;

    // ShortArray allozieren (NICHT im Audio-Callback — hier ist JNI-Thread, das ist ok)
    jshortArray result = env->NewShortArray(numSamples);
    if (result == nullptr) return nullptr;

    // Temporaerer Stack-Buffer fuer kleine Chunks, Heap fuer grosse
    // Grenze: 64 KB auf dem Stack (32768 Samples * 2 Bytes)
    constexpr int32_t STACK_LIMIT = 32768;

    if (numSamples <= STACK_LIMIT) {
        int16_t stackBuffer[STACK_LIMIT];
        int32_t copied = gEngine.getLatestSamples(numSamples, stackBuffer);
        if (copied > 0) {
            env->SetShortArrayRegion(result, 0, copied, stackBuffer);
        }
        // Rest bleibt 0 (Java-Array ist zero-initialized)
    } else {
        // Grosser Chunk — Heap-Allokation (selten, z.B. 30s bei 96 kHz = 2.88M Samples)
        auto* heapBuffer = new int16_t[numSamples];
        int32_t copied = gEngine.getLatestSamples(numSamples, heapBuffer);
        if (copied > 0) {
            env->SetShortArrayRegion(result, 0, copied, heapBuffer);
        }
        delete[] heapBuffer;
    }

    return result;
}

JNIEXPORT jint JNICALL
Java_ch_etasystems_pirol_audio_OboeAudioEngine_nativeGetActualSampleRate(
        JNIEnv* /*env*/, jobject /*thiz*/) {
    return gEngine.getActualSampleRate();
}

JNIEXPORT void JNICALL
Java_ch_etasystems_pirol_audio_OboeAudioEngine_nativeSetBufferDurationSeconds(
        JNIEnv* /*env*/, jobject /*thiz*/, jint seconds) {
    gEngine.setBufferDurationSeconds(seconds);
}

} // extern "C"
