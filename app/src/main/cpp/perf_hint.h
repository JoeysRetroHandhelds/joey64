#pragma once
#include <cstdint>
#include <vector>

struct APerformanceHintSession;

// ADPF performance hint session (Android 13+) for the emulation thread. Each frame is a burst of work followed by
// a wait for vsync/audio, so the scheduler sees a half-idle thread and lowers its clocks or moves it, and the next
// burst runs late. Reporting each frame's CPU time against a target tells it what the frame needs (PPSSPP does the
// same). Does nothing where the API is missing.
class PerfHint {
public:
    ~PerfHint();
    // Once per frame, always from the same thread; the session is created on the first call.
    void reportFrame();
    // EXPERIMENT (N64_PERF_HINT=2, WatermelonDS): put these threads (the core's workers) in the same session as
    // the emulation thread. Call from the emulation thread after the session exists.
    void addThreads(const std::vector<int32_t>& tids);
    // Target work time per frame; lower it when frames run faster than 60 fps (fast-forward, uncapped bench).
    void setTargetNs(int64_t ns);

private:
    APerformanceHintSession* session_ = nullptr;
    bool failed_ = false;
    int64_t lastCpuNs_ = -1;
    int64_t targetNs_ = 0;
};
