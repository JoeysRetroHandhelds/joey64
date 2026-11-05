#pragma once
#include <aaudio/AAudio.h>
#include <atomic>
#include <cstdint>
#include <mutex>
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
    // Audio sync, called before each emulated frame: blocks until the ring holds at most the target
    // plus one frame, so the wait lands before the frame polls input rather than in the middle of it.
    // Also reopens the stream when the output device went away (see onError).
    void waitForRoom();
    // Device-side output latency in ms (AAudio timestamps: frames written but not yet played),
    // -1 if unknown. AAudio's buffer starts at 2 bursts and grows a burst per xrun.
    double outputLatencyMs();
    // Stats since the last call: mean ring fill and target in ms, underrun callbacks. keepingUp =
    // the emulation ran at full speed meanwhile: underruns then raise the target by 10 ms (devices
    // whose frames sometimes miss a vsync need more queued), up to kMaxTargetMs.
    void takeStats(bool keepingUp, double& fillMs, double& targetMs, int& underruns);

private:
    static constexpr int kTargetMs = 20, kMaxTargetMs = 150;
    static aaudio_data_callback_result_t cb(AAudioStream*, void* user, void* data, int32_t frames);
    // A headset or Bluetooth device coming or going, or another route change, disconnects the
    // stream: its callbacks stop, so the ring would fill and every write stall. The error callback
    // only flags it (a stream can't be closed from its own callbacks); the emulation thread reopens
    // on the default device at the next frame, and retries once a second if that fails.
    static void onError(AAudioStream*, void* user, aaudio_result_t error);
    bool openStream();
    void reopenIfLost();
    std::mutex streamMutex_;  // stream_ lifetime vs setPaused (UI thread)
    std::atomic<bool> lost_{false};
    bool paused_ = false;
    int srcRate_ = 0;
    int64_t retryNs_ = 0;     // when to try opening again after a failed reopen (0 = not failed)
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
    int xruns_ = 0;          // AAudio xrun count at the last check
    // N64_AUDIO_SMOOTH=1 (experiment): games that hand over audio unevenly between frames (Perfect Dark: 0, then
    // two frames' worth, every other frame) made audio sync start frames ~5 then ~28 ms apart (judder on 120 Hz).
    // The wait then compares the fill minus excess_, a fading sum of each frame's audio above the running average.
    bool smooth_ = false;
    size_t lastW_ = 0;       // write index at the last waitForRoom
    double avgWrite_ = -1;   // running average of samples written per frame (-1 = none yet)
    double excess_ = 0;      // fading sum of (written - average), samples
    double step_ = 1.0;      // source frames per output frame
    double pos_ = 0.0;       // position between prev_ and the next source frame
    int16_t prev_[2] = {0, 0};
};
