#pragma once
#include <aaudio/AAudio.h>
#include <atomic>
#include <cstdint>
#include <vector>

// AAudio low-latency output fed by a lock-free SPSC ring buffer of interleaved stereo float
// (float matches the device mixer, which the fast path needs).
// The stream runs at the device's native rate (so Android grants the fast path); write()
// linearly resamples from the core's rate.
class AudioOut {
public:
    bool open(int srcRate);
    void close();
    // Called from the emulation thread. Blocks briefly if the ring is full (acts as audio sync).
    void write(const int16_t* frames, size_t frameCount);
    void setPaused(bool p);

private:
    static aaudio_data_callback_result_t cb(AAudioStream*, void* user, void* data, int32_t frames);
    AAudioStream* stream_ = nullptr;
    std::vector<float> ring_;
    size_t cap_ = 0; // in samples
    std::atomic<size_t> r_{0}, w_{0};
    bool push(float s);
    double step_ = 1.0;      // source frames per output frame
    double pos_ = 0.0;       // position between prev_ and the next source frame
    int16_t prev_[2] = {0, 0};
};
