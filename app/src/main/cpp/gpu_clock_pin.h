#pragma once
#include <atomic>
#include <thread>

// EXPERIMENT (N64_GPU_TURBO=2): ask the Adreno kernel driver (KGSL) to hold the GPU at its top allowed power level
// while a game runs. A KGSL power constraint belongs to a GPU context and holds only while that context has work,
// so a separate small context submits an empty command buffer every 25 ms. Idea from DroidDeck (GPL-3: written
// here from the kernel's msm_kgsl.h interface, no code taken). The level can't go above the driver's current cap
// (kgsl max_pwrlevel; the Duo is capped at 975 of 1150 MHz). Does nothing on non-Adreno devices.
class GpuClockPin {
public:
    ~GpuClockPin() { stop(); }
    bool start();
    void stop();

private:
    bool open();
    void close();
    bool submit();
    void keepAlive();

    int fd_ = -1;
    unsigned int context_ = 0, buffer_ = 0, timestamp_ = 0;
    unsigned long bufferGpuAddr_ = 0;
    void* bufferMap_ = nullptr;
    size_t bufferMapSize_ = 0;
    std::atomic<bool> running_{false};
    std::thread thread_;
};
