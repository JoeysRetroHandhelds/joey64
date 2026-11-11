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
    // Fast-forward sound (Settings › Audio): never blocks. Resamples [speed] times faster (sped up, higher
    // pitch), steered by the ring's fill so a game running below or above that speed neither runs dry nor
    // piles up; what doesn't fit is dropped. [gain] scales it (Quieter).
    void writeFast(const int16_t* frames, size_t frameCount, double speed, float gain);
    // Settings › Audio, any thread, applied in the callback (heard at once): volume 0-100 (a loudness
    // curve: gain = (v/100)^2), mute, mono (L+R averaged).
    void setMix(int volumePct, bool mute, bool mono);
    // Latency (Settings › Audio), read when the stream opens: 0 Auto (20 ms, grows to 150 on
    // underruns at full speed, as before), 1 Low (10 ms, never grows), 2 Safe (60 ms, grows to 150).
    void setLatencyMode(int mode) { latencyMode_ = mode; }
    void setPaused(bool p);
    // Audio sync, called before each emulated frame: blocks until the ring holds at most the target
    // plus one frame, so the wait lands before the frame polls input rather than in the middle of it.
    // Also reopens the stream when the output device went away (see onError).
    void waitForRoom();
    // The panel's refresh period (0 = unknown), each frame before waitForRoom: smoothing is automatic on panels
    // faster than 60 Hz (see smooth_).
    void setPanelRefreshNs(uint64_t ns);
    // Device-side output latency in ms (AAudio timestamps: frames written but not yet played),
    // -1 if unknown. AAudio's buffer starts at 2 bursts and grows a burst per xrun.
    double outputLatencyMs();
    // Stats since the last call: mean ring fill and target in ms, underrun callbacks. keepingUp =
    // the emulation ran at full speed meanwhile: underruns then raise the target by 10 ms (devices
    // whose frames sometimes miss a vsync need more queued), up to kMaxTargetMs.
    void takeStats(bool keepingUp, double& fillMs, double& targetMs, int& underruns);
    // Audio frames per second since the last call: from the game (write) and taken by the device's callbacks. The
    // T820 report (2026-11-08) drained the ring at 61 emulated fps in windows where half the frames repeat; these
    // tell a game that brings less audio from a device that plays faster than its stated rate.
    void takeRates(double secs, double& inHz, double& outHz);

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
    bool stalled_ = false;    // push gave up on a full ring; the emulation thread's own (push, open)
    bool paused_ = false;
    int srcRate_ = 0;
    int64_t retryNs_ = 0;     // when to try opening again after a failed reopen (0 = not failed)
    AAudioStream* stream_ = nullptr;
    std::vector<float> ring_;
    size_t cap_ = 0; // in samples
    std::atomic<size_t> r_{0}, w_{0};
    bool push(float s);
    std::atomic<float> gain_{1.0f};
    std::atomic<bool> mono_{false};
    std::atomic<int> latencyMode_{0};
    bool grow_ = true;       // underruns may raise the target (not on Low)
    size_t fill() const { return (w_.load(std::memory_order_acquire) + cap_ - r_.load(std::memory_order_acquire)) % cap_; }
    int dstRate_ = 48000;
    size_t target_ = 0;      // samples the ring is kept near (audio latency)
    std::atomic<int> underruns_{0};
    std::atomic<long long> srcFrames_{0}, outFrames_{0}; // takeRates
    double fillSum_ = 0; int fillCount_ = 0;
    double baseStep_ = 1.0;  // nominal source frames per output frame
    double drcI_ = 0;        // integral part of the rate correction
    int frames_ = 0;         // waitForRoom calls since open (startup frames don't adapt the target)
    int xruns_ = 0;          // AAudio xrun count at the last check
    // Smoothed audio wait: games that hand over audio unevenly between frames (Perfect Dark: 0, then two frames'
    // worth, every other frame) made audio sync start frames ~5 then ~28 ms apart (judder on 120 Hz). The wait then
    // compares the fill minus excess_, a fading sum of each frame's audio above the running average. On by default
    // on panels faster than 60 Hz (Duo/Odin 3: Perfect Dark uneven 244-246 -> 28-39 per 5 s, lag 1.6-2.6 ms lower,
    // other games unchanged); off at 60 Hz, where vsync paces and it gained nothing (Flip). N64_AUDIO_SMOOTH=1/0
    // forces it on/off. smoothMode_: -1 auto, 0 off, 1 on.
    int smoothMode_ = -1;
    bool smooth_ = false;
    size_t lastW_ = 0;       // write index at the last waitForRoom
    double avgWrite_ = -1;   // running average of samples written per frame (-1 = none yet)
    double excess_ = 0;      // fading sum of (written - average), samples
    double step_ = 1.0;      // source frames per output frame
    double pos_ = 0.0;       // position between prev_ and the next source frame
    int16_t prev_[2] = {0, 0};
};
