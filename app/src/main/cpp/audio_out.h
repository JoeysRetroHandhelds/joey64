#pragma once
#include <aaudio/AAudio.h>
#include <atomic>
#include <cstdint>
#include <vector>

// AAudio low-latency output fed by a lock-free SPSC ring buffer of interleaved stereo int16.
class AudioOut {
public:
    bool open(int sampleRate);
    void close();
    // Called from the emulation thread. Blocks briefly if the ring is full (acts as audio sync).
    void write(const int16_t* frames, size_t frameCount);
    void setPaused(bool p);

private:
    static aaudio_data_callback_result_t cb(AAudioStream*, void* user, void* data, int32_t frames);
    AAudioStream* stream_ = nullptr;
    std::vector<int16_t> ring_;
    size_t cap_ = 0; // in samples
    std::atomic<size_t> r_{0}, w_{0};
};
