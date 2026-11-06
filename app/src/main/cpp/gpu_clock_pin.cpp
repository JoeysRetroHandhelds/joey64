#include "joeylog.h"
#include "gpu_clock_pin.h"
#include <android/log.h>
#include <cerrno>
#include <chrono>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <unistd.h>
#include "third_party/libadrenotools/src/hook/kgsl.h"

#define LOGI(...) joeyLog(ANDROID_LOG_INFO, "joey64host", __VA_ARGS__)

namespace {
// CP_NOP (opcode 0x10) with one payload word. a5xx and newer use type-7 packets: count in bits 0-14, opcode in
// bits 16-22, each with an odd-parity bit (bits 15 and 23; both values here have one bit set, so both are 0).
// a3xx/a4xx use type-3 packets: count-1 in bits 16-29, opcode in bits 8-15.
uint32_t nopHeader(unsigned int gpuGeneration) {
    return gpuGeneration >= 5 ? 0x70000000u | (0x10u << 16) | 1u : 0xC0000000u | (0x10u << 8);
}
}

bool GpuClockPin::open() {
    fd_ = ::open("/dev/kgsl-3d0", O_RDWR | O_CLOEXEC);
    if (fd_ < 0) { LOGI("GPU clock pin: no KGSL device (%s)", strerror(errno)); return false; }

    kgsl_devinfo info{};
    kgsl_device_getproperty infoProp{KGSL_PROP_DEVICE_INFO, &info, sizeof info};
    if (ioctl(fd_, IOCTL_KGSL_DEVICE_GETPROPERTY, &infoProp) < 0) { LOGI("GPU clock pin: device info failed (%s)", strerror(errno)); return false; }
    unsigned int generation = info.chip_id >> 24;
    if (generation == 0) generation = info.gpu_id / 100;
    if (generation < 3) { LOGI("GPU clock pin: unknown Adreno generation (chip %08x gpu %u)", info.chip_id, info.gpu_id); return false; }

    kgsl_drawctxt_create ctx{};
    ctx.flags = KGSL_CONTEXT_NO_GMEM_ALLOC | KGSL_CONTEXT_PREAMBLE | KGSL_CONTEXT_PWR_CONSTRAINT;
    if (ioctl(fd_, IOCTL_KGSL_DRAWCTXT_CREATE, &ctx) < 0) { LOGI("GPU clock pin: context failed (%s)", strerror(errno)); return false; }
    context_ = ctx.drawctxt_id;

    kgsl_device_constraint_pwrlevel level{KGSL_CONSTRAINT_PWR_MAX};
    kgsl_device_constraint constraint{};
    constraint.type = KGSL_CONSTRAINT_PWRLEVEL;
    constraint.context_id = context_;
    constraint.data = &level;
    constraint.size = sizeof level;
    kgsl_device_getproperty constraintProp{KGSL_PROP_PWR_CONSTRAINT, &constraint, sizeof constraint};
    if (ioctl(fd_, IOCTL_KGSL_SETPROPERTY, &constraintProp) < 0) { LOGI("GPU clock pin: constraint refused (%s)", strerror(errno)); return false; }

    kgsl_gpumem_alloc_id mem{};
    mem.size = 4096;
    if (ioctl(fd_, IOCTL_KGSL_GPUMEM_ALLOC_ID, &mem) < 0) { LOGI("GPU clock pin: buffer failed (%s)", strerror(errno)); return false; }
    buffer_ = mem.id;
    bufferGpuAddr_ = mem.gpuaddr;
    bufferMapSize_ = mem.mmapsize;
    void* map = mmap(nullptr, bufferMapSize_, PROT_READ | PROT_WRITE, MAP_SHARED, fd_, (off_t)buffer_ << 12);
    if (map == MAP_FAILED) { LOGI("GPU clock pin: map failed (%s)", strerror(errno)); return false; }
    bufferMap_ = map;
    auto* words = static_cast<uint32_t*>(map);
    words[0] = nopHeader(generation);
    words[1] = 0;
    kgsl_gpumem_sync_cache sync{};
    sync.id = buffer_;
    sync.op = KGSL_GPUMEM_CACHE_CLEAN;
    if (ioctl(fd_, IOCTL_KGSL_GPUMEM_SYNC_CACHE, &sync) < 0) { LOGI("GPU clock pin: cache clean failed (%s)", strerror(errno)); return false; }
    LOGI("GPU clock pin: context %u on Adreno %u (gen %u)", context_, info.gpu_id, generation);
    return true;
}

bool GpuClockPin::submit() {
    kgsl_command_object ib{};
    ib.gpuaddr = bufferGpuAddr_;
    ib.size = 2 * sizeof(uint32_t);
    ib.flags = KGSL_CMDLIST_IB;
    ib.id = buffer_;
    kgsl_gpu_command cmd{};
    cmd.flags = KGSL_CMDBATCH_PWR_CONSTRAINT;
    cmd.cmdlist = reinterpret_cast<uintptr_t>(&ib);
    cmd.cmdsize = sizeof ib;
    cmd.numcmds = 1;
    cmd.context_id = context_;
    if (ioctl(fd_, IOCTL_KGSL_GPU_COMMAND, &cmd) < 0) return false;
    timestamp_ = cmd.timestamp;
    return true;
}

void GpuClockPin::keepAlive() {
    int submits = 0;
    while (running_.load()) {
        kgsl_device_waittimestamp_ctxtid wait{};
        wait.context_id = context_;
        wait.timestamp = timestamp_;
        wait.timeout = 100; // ms
        if (ioctl(fd_, IOCTL_KGSL_DEVICE_WAITTIMESTAMP_CTXTID, &wait) < 0 && errno != EINTR && errno != ETIMEDOUT) {
            LOGI("GPU clock pin: wait failed (%s), stopping", strerror(errno));
            break;
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(25));
        if (!running_.load()) break;
        if (!submit()) { LOGI("GPU clock pin: submit failed (%s), stopping", strerror(errno)); break; }
        if (++submits == 40) LOGI("GPU clock pin: holding (40 submits)");
    }
    running_ = false;
}

void GpuClockPin::close() {
    if (context_) { kgsl_drawctxt_destroy d{}; d.drawctxt_id = context_; ioctl(fd_, IOCTL_KGSL_DRAWCTXT_DESTROY, &d); }
    if (bufferMap_) munmap(bufferMap_, bufferMapSize_);
    if (buffer_) { kgsl_gpumem_free_id f{}; f.id = buffer_; ioctl(fd_, IOCTL_KGSL_GPUMEM_FREE_ID, &f); }
    if (fd_ >= 0) ::close(fd_);
    fd_ = -1; context_ = buffer_ = timestamp_ = 0; bufferGpuAddr_ = 0; bufferMap_ = nullptr; bufferMapSize_ = 0;
}

bool GpuClockPin::start() {
    if (running_.load()) return true;
    if (!open() || !submit()) { if (fd_ >= 0 && context_) LOGI("GPU clock pin: first submit failed (%s)", strerror(errno)); close(); return false; }
    running_ = true;
    thread_ = std::thread([this] { keepAlive(); });
    return true;
}

void GpuClockPin::stop() {
    running_ = false;
    if (thread_.joinable()) thread_.join();
    close();
}
