#pragma once

#include <cstdint>
#include <cstring>
#include <atomic>
#include <algorithm>
#include <vector>

/**
 * SPSC (Single-Producer, Single-Consumer) Ring-Buffer fuer Audio-Samples.
 *
 * Writer: Oboe Realtime-Callback-Thread (onAudioReady)
 * Reader: JNI-Thread (getLatestSamples)
 *
 * Lock-free Design:
 * - Writer schreibt Samples und aktualisiert writePos (atomic)
 * - Reader liest die juengsten N Samples basierend auf writePos-Snapshot
 * - Ueberschreibend: aelteste Daten werden verworfen wenn Buffer voll
 */
class RingBuffer {
public:
    RingBuffer() : buffer_(0), capacity_(0), writePos_(0), totalWritten_(0) {}

    /**
     * Buffer-Groesse setzen. NUR vor start() aufrufen!
     * Nicht thread-safe — darf nicht waehrend Aufnahme aufgerufen werden.
     */
    void resize(int32_t capacitySamples) {
        capacity_ = capacitySamples;
        buffer_.resize(capacitySamples, 0);
        writePos_.store(0, std::memory_order_relaxed);
        totalWritten_.store(0, std::memory_order_relaxed);
    }

    /**
     * Samples in den Ring-Buffer schreiben.
     * Aufgerufen vom Oboe Realtime-Thread — KEINE Allokationen!
     */
    void write(const int16_t* data, int32_t numSamples) {
        if (capacity_ == 0 || numSamples == 0) return;

        int32_t pos = writePos_.load(std::memory_order_relaxed);

        // Falls mehr Samples als Buffer-Kapazitaet: nur die letzten capacity_ Samples schreiben
        if (numSamples > capacity_) {
            data += (numSamples - capacity_);
            numSamples = capacity_;
        }

        // Schreiben, ggf. mit Wrap-around
        int32_t firstChunk = std::min(numSamples, capacity_ - pos);
        std::memcpy(buffer_.data() + pos, data, firstChunk * sizeof(int16_t));

        if (firstChunk < numSamples) {
            // Wrap-around: Rest am Anfang schreiben
            std::memcpy(buffer_.data(), data + firstChunk, (numSamples - firstChunk) * sizeof(int16_t));
        }

        int32_t newPos = (pos + numSamples) % capacity_;
        writePos_.store(newPos, std::memory_order_release);
        totalWritten_.fetch_add(numSamples, std::memory_order_relaxed);
    }

    /**
     * Die juengsten numSamples aus dem Buffer lesen.
     * Thread-safe gegenueber dem Writer-Thread.
     *
     * @param outBuffer Ziel-Array (muss numSamples gross sein)
     * @param numSamples Anzahl gewuenschte Samples
     * @return Tatsaechlich kopierte Samples (kann < numSamples sein wenn Buffer noch nicht voll)
     */
    int32_t readLatest(int16_t* outBuffer, int32_t numSamples) const {
        if (capacity_ == 0 || numSamples == 0) return 0;

        int64_t total = totalWritten_.load(std::memory_order_acquire);
        int32_t pos = writePos_.load(std::memory_order_acquire);

        // Verfuegbare Samples: min(totalWritten, capacity)
        int32_t available = static_cast<int32_t>(std::min(static_cast<int64_t>(capacity_), total));
        int32_t toCopy = std::min(numSamples, available);

        if (toCopy == 0) return 0;

        // Startposition berechnen: pos zeigt auf naechste Schreibposition,
        // die juengsten toCopy Samples liegen davor
        int32_t startPos = (pos - toCopy + capacity_) % capacity_;

        // Lesen, ggf. mit Wrap-around
        int32_t firstChunk = std::min(toCopy, capacity_ - startPos);
        std::memcpy(outBuffer, buffer_.data() + startPos, firstChunk * sizeof(int16_t));

        if (firstChunk < toCopy) {
            std::memcpy(outBuffer + firstChunk, buffer_.data(), (toCopy - firstChunk) * sizeof(int16_t));
        }

        return toCopy;
    }

    int32_t capacity() const { return capacity_; }

    int32_t availableSamples() const {
        int64_t total = totalWritten_.load(std::memory_order_acquire);
        return static_cast<int32_t>(std::min(static_cast<int64_t>(capacity_), total));
    }

private:
    std::vector<int16_t> buffer_;
    int32_t capacity_;
    std::atomic<int32_t> writePos_;
    std::atomic<int64_t> totalWritten_;
};
