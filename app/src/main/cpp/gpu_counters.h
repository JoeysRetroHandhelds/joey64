#pragma once
#include <atomic>
#include <cstdint>
#include <thread>

// DEV MEASUREMENT (N64_GPU_COUNTERS=1, master list item 30): Adreno GPU clock and busy % from inside the app.
// KGSL lets any app reserve a performance counter but blocks reading it back (sysfs perfcounter is 0 on our
// devices), and the Duo's gpubusy sysfs is frozen. So a KGSL context of our own has the GPU copy the counters into
// memory: every 100 ms a 3-packet command buffer runs CP_REG_TO_MEM on CP_ALWAYS_ON_COUNTER (19.2 MHz),
// RBBM ALWAYS_COUNT (GPU cycles) and RBBM STATUS_MASKED (GPU busy cycles). Every 5 s the deltas are logged:
// clock = cycles / time, busy = busy cycles / cycles. KGSL clears the counters when another process uses the GPU,
// so a sample whose counters went backwards or out of range is dropped (the log gives the count).
// Countables from the kernel's msm a6xx/a7xx_perfcntrs.xml (0 ALWAYS_COUNT, 6 STATUS_MASKED); idea from DroidDeck
// (GPL-3: written here from msm_kgsl.h and the PM4 packet format, no code taken). A8xx (Odin 3) is not handled:
// its busy countable is not known here. Does nothing on non-Adreno devices.
class GpuCounters {
public:
    ~GpuCounters() { stop(); }
    bool start();
    void stop();

private:
    bool open();
    void close();
    bool sample(uint64_t out[3]);
    void loop();

    int fd_ = -1;
    unsigned int context_ = 0, buffer_ = 0, timestamp_ = 0;
    uint64_t bufferGpuAddr_ = 0;
    void* bufferMap_ = nullptr;
    size_t bufferMapSize_ = 0;
    unsigned int alwaysOnReg_ = 0, cyclesReg_ = 0, busyReg_ = 0, scanReg_ = 0, ibDwords_ = 12;
    std::atomic<bool> running_{false};
    std::thread thread_;
};
