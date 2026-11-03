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
    // Called from the emulation thread. Only blocks if the ring is completely full.
    void write(const int16_t* frames, size_t frameCount);
    void setPaused(bool p);
    // Audio sync, called before each emulated frame: blocks until the ring holds at most
    // targetMs_, so the wait lands before the frame polls input rather than in the middle of it.
    void waitForRoom();
    // Stats since the last call: mean ring fill and target in ms, underrun callbacks. keepingUp =
    // the emulation ran at full speed meanwhile: underruns then raise the target by 10 ms (devices
    // whose frames sometimes miss a vsync need more queued), up to kMaxTargetMs.
    void takeStats(bool keepingUp, double& fillMs, double& targetMs, int& underruns);

private:
    static constexpr int kTargetMs = 50, kMaxTargetMs = 150;
    static aaudio_data_callback_result_t cb(AAudioStream*, void* user, void* data, int32_t frames);
    AAudioStream* stream_ = nullptr;
    std::vector<float> ring_;
    size_t cap_ = 0; // in samples
    std::atomic<size_t> r_{0}, w_{0};
    bool push(float s);
    size_t fill() const { return (w_.load(std::memory_order_acquire) + cap_ - r_.load(std::memory_order_acquire)) % cap_; }
    int dstRate_ = 48000;
    size_t target_ = 0;      // samples the ring is kept near (audio latency)
    std::atomic<int> underruns_{0};
    double fillSum_ = 0; int fillCount_ = 0;
    double baseStep_ = 1.0;  // nominal source frames per output frame
    double drcI_ = 0;        // integral part of the rate correction
    int frames_ = 0;         // waitForRoom calls since open (startup frames don't adapt the target)
    double step_ = 1.0;      // source frames per output frame
    double pos_ = 0.0;       // position between prev_ and the next source frame
    int16_t prev_[2] = {0, 0};
};
