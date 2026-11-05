#include "gpu_counters.h"
#include <android/log.h>
#include <cerrno>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <unistd.h>
#include "third_party/libadrenotools/src/hook/kgsl.h"

#define LOGI(...) __android_log_print(getenv("N64_LOG_ERR") && *getenv("N64_LOG_ERR") == '1' ? ANDROID_LOG_ERROR : ANDROID_LOG_INFO, "joey64host", __VA_ARGS__)

namespace {
constexpr unsigned kCpAlwaysOnCounterA6xx = 0x980; // CP_ALWAYS_ON_COUNTER (lo; hi = +1), a6xx/a7xx
constexpr unsigned kReadableLast = 0x4ff;          // CP-protected registers above this can't be read by an IB (gen7)
constexpr unsigned kCountableAlwaysCount = 0;      // PERF_RBBM_ALWAYS_COUNT
constexpr unsigned kCountableStatusMasked = 6;     // PERF_RBBM_STATUS_MASKED (GPU busy)
constexpr double kAlwaysOnHz = 19.2e6;
constexpr size_t kResultOffset = 2048;             // results in the second half of the 4 KB buffer

unsigned oddParity(unsigned v) { // PM4 type-7 parity bit: makes the field plus this bit an odd count of ones
    return (__builtin_popcount(v) & 1) ? 0u : 1u;
}
uint32_t pkt7(unsigned opcode, unsigned count) {
    return 0x70000000u | (oddParity(opcode) << 23) | (opcode << 16) | (oddParity(count) << 15) | count;
}
constexpr unsigned kCpRegToMem = 0x3e;
}

bool GpuCounters::open() {
    fd_ = ::open("/dev/kgsl-3d0", O_RDWR | O_CLOEXEC);
    if (fd_ < 0) { LOGI("GPU counters: no KGSL device (%s)", strerror(errno)); return false; }
    kgsl_devinfo info{};
    kgsl_device_getproperty infoProp{KGSL_PROP_DEVICE_INFO, &info, sizeof info};
    if (ioctl(fd_, IOCTL_KGSL_DEVICE_GETPROPERTY, &infoProp) < 0) { LOGI("GPU counters: device info failed (%s)", strerror(errno)); return false; }
    unsigned core = info.chip_id >> 24;
    if (core >= 0x44u) { LOGI("GPU counters: A8xx or later (chip %08x) not handled", info.chip_id); return false; }
    unsigned gen = core == 0x43u ? 7 : core;
    if (gen < 6) { LOGI("GPU counters: Adreno before A6xx (chip %08x) not handled", info.chip_id); return false; }
    alwaysOnReg_ = kCpAlwaysOnCounterA6xx;

    kgsl_perfcounter_get cyc{KGSL_PERFCOUNTER_GROUP_RBBM, kCountableAlwaysCount, 0, 0, 0};
    if (ioctl(fd_, IOCTL_KGSL_PERFCOUNTER_GET, &cyc) < 0) { LOGI("GPU counters: cycles counter refused (%s)", strerror(errno)); return false; }
    kgsl_perfcounter_get busy{KGSL_PERFCOUNTER_GROUP_RBBM, kCountableStatusMasked, 0, 0, 0};
    if (ioctl(fd_, IOCTL_KGSL_PERFCOUNTER_GET, &busy) < 0) { LOGI("GPU counters: busy counter refused (%s)", strerror(errno)); return false; }
    cyclesReg_ = cyc.offset; busyReg_ = busy.offset;
    if (!cyclesReg_ || !busyReg_ || cyc.offset_hi != cyclesReg_ + 1 || busy.offset_hi != busyReg_ + 1 ||
        busyReg_ + 1 > kReadableLast || cyclesReg_ + 1 > kReadableLast) {
        LOGI("GPU counters: counter registers not readable (cycles %x/%x, busy %x/%x)", cyc.offset, cyc.offset_hi, busy.offset, busy.offset_hi);
        return false;
    }

    kgsl_drawctxt_create ctx{};
    ctx.flags = KGSL_CONTEXT_NO_GMEM_ALLOC | KGSL_CONTEXT_PREAMBLE;
    if (ioctl(fd_, IOCTL_KGSL_DRAWCTXT_CREATE, &ctx) < 0) { LOGI("GPU counters: context failed (%s)", strerror(errno)); return false; }
    context_ = ctx.drawctxt_id;

    kgsl_gpumem_alloc_id mem{};
    mem.size = 4096;
    if (ioctl(fd_, IOCTL_KGSL_GPUMEM_ALLOC_ID, &mem) < 0) { LOGI("GPU counters: buffer failed (%s)", strerror(errno)); return false; }
    buffer_ = mem.id; bufferGpuAddr_ = mem.gpuaddr; bufferMapSize_ = mem.mmapsize;
    void* map = mmap(nullptr, bufferMapSize_, PROT_READ | PROT_WRITE, MAP_SHARED, fd_, (off_t)buffer_ << 12);
    if (map == MAP_FAILED) { LOGI("GPU counters: map failed (%s)", strerror(errno)); return false; }
    bufferMap_ = map;

    // Three CP_REG_TO_MEM packets: 2 registers (lo, hi) each, 64-bit destination address.
    auto* w = static_cast<uint32_t*>(map);
    const unsigned regs[3] = {alwaysOnReg_, cyclesReg_, busyReg_};
    unsigned n = 0;
    for (unsigned i = 0; i < 3; i++) {
        uint64_t dst = bufferGpuAddr_ + kResultOffset + i * 8;
        w[n++] = pkt7(kCpRegToMem, 3);
        w[n++] = regs[i] | (2u << 18) | (1u << 30); // REG, CNT = 2, 64B
        w[n++] = (uint32_t)dst;
        w[n++] = (uint32_t)(dst >> 32);
    }
    // Debug (N64_GPU_COUNTERS_SCAN=<hex register>): also copy 16 registers from there (to find counters that run)
    if (const char* scan = getenv("N64_GPU_COUNTERS_SCAN")) {
        scanReg_ = (unsigned)strtoul(scan, nullptr, 16);
        if (scanReg_ && scanReg_ + 15 <= kReadableLast) {
            uint64_t dst = bufferGpuAddr_ + kResultOffset + 64;
            w[n++] = pkt7(kCpRegToMem, 3);
            w[n++] = scanReg_ | (16u << 18) | (1u << 30);
            w[n++] = (uint32_t)dst;
            w[n++] = (uint32_t)(dst >> 32);
        } else {
            scanReg_ = 0;
        }
    }
    ibDwords_ = n;
    kgsl_gpumem_sync_cache sync{};
    sync.id = buffer_;
    sync.op = KGSL_GPUMEM_CACHE_CLEAN;
    if (ioctl(fd_, IOCTL_KGSL_GPUMEM_SYNC_CACHE, &sync) < 0) { LOGI("GPU counters: cache clean failed (%s)", strerror(errno)); return false; }
    LOGI("GPU counters: Adreno %u (chip %08x), cycles reg %x, busy reg %x", info.gpu_id, info.chip_id, cyclesReg_, busyReg_);
    return true;
}

bool GpuCounters::sample(uint64_t out[3]) {
    auto* res = reinterpret_cast<volatile uint64_t*>(static_cast<uint8_t*>(bufferMap_) + kResultOffset);
    for (int i = 0; i < 3; i++) res[i] = 0;
    kgsl_gpumem_sync_cache clean{};
    clean.id = buffer_; clean.op = KGSL_GPUMEM_CACHE_CLEAN;
    ioctl(fd_, IOCTL_KGSL_GPUMEM_SYNC_CACHE, &clean);

    kgsl_command_object ib{};
    ib.gpuaddr = bufferGpuAddr_;
    ib.size = ibDwords_ * sizeof(uint32_t);
    ib.flags = KGSL_CMDLIST_IB;
    ib.id = buffer_;
    kgsl_gpu_command cmd{};
    cmd.cmdlist = reinterpret_cast<uintptr_t>(&ib);
    cmd.cmdsize = sizeof ib;
    cmd.numcmds = 1;
    cmd.context_id = context_;
    if (ioctl(fd_, IOCTL_KGSL_GPU_COMMAND, &cmd) < 0) return false;
    timestamp_ = cmd.timestamp;
    kgsl_device_waittimestamp_ctxtid wait{};
    wait.context_id = context_;
    wait.timestamp = timestamp_;
    wait.timeout = 200;
    if (ioctl(fd_, IOCTL_KGSL_DEVICE_WAITTIMESTAMP_CTXTID, &wait) < 0) return false;
    kgsl_gpumem_sync_cache inv{};
    inv.id = buffer_; inv.op = KGSL_GPUMEM_CACHE_INV;
    ioctl(fd_, IOCTL_KGSL_GPUMEM_SYNC_CACHE, &inv);
    for (int i = 0; i < 3; i++) out[i] = res[i];
    return out[0] != 0;
}

void GpuCounters::loop() {
    uint64_t prev[3] = {}, cur[3];
    bool havePrev = false;
    double xoSum = 0, cycSum = 0, busySum = 0;
    int good = 0, dropped = 0;
    int rawLeft = 5; // N64_GPU_COUNTERS_RAW=1 logs the first raw samples
    int stillSamples = 0; // samples in a row where the cycle counter read 0
    auto windowStart = std::chrono::steady_clock::now();
    while (running_.load()) {
        std::this_thread::sleep_for(std::chrono::milliseconds(100));
        if (!sample(cur)) { dropped++; havePrev = false; continue; }
        // Portal (Adreno 740, 2026-11-05): KGSL hands out the counters but they never run while its perfcounter
        // switch is 0; every RBBM counter register (0x31c-0x32b) reads 0. Stop instead of logging zeros.
        if (cur[1] == 0 && ++stillSamples >= 30) {
            LOGI("GPU counters: the GPU cycle counter stays at 0 (KGSL perfcounter off?), stopping");
            break;
        }
        if (getenv("N64_GPU_COUNTERS_RAW") && rawLeft > 0) {
            rawLeft--;
            LOGI("GPU counters raw: always-on %llu, cycles %llu, busy %llu", (unsigned long long)cur[0],
                 (unsigned long long)cur[1], (unsigned long long)cur[2]);
            if (scanReg_) {
                auto* r = reinterpret_cast<volatile uint32_t*>(static_cast<uint8_t*>(bufferMap_) + kResultOffset + 64);
                char line[400]; int len = 0;
                for (int i = 0; i < 16; i++) len += snprintf(line + len, sizeof line - len, " %x:%08x", scanReg_ + i, r[i]);
                LOGI("GPU counters scan:%s", line);
            }
        }
        if (havePrev) {
            uint64_t dxo = cur[0] - prev[0], dcyc = cur[1] - prev[1], dbusy = cur[2] - prev[2];
            double secs = dxo / kAlwaysOnHz;
            // valid: time moved 50-500 ms, counters moved forward, clock under 2 GHz, busy within cycles
            if (cur[0] > prev[0] && cur[1] >= prev[1] && cur[2] >= prev[2] && secs > 0.05 && secs < 0.5 &&
                dcyc < secs * 2e9 && dbusy <= dcyc) {
                xoSum += dxo; cycSum += dcyc; busySum += dbusy; good++;
            } else {
                dropped++;
            }
        }
        for (int i = 0; i < 3; i++) prev[i] = cur[i];
        havePrev = true;
        auto now = std::chrono::steady_clock::now();
        if (now - windowStart >= std::chrono::seconds(5)) {
            double secs = xoSum / kAlwaysOnHz;
            if (good > 0 && secs > 0)
                LOGI("GPU counters: clock %.0f MHz, busy %.1f%% (%.0f busy MHz), %d samples, %d dropped",
                     cycSum / secs / 1e6, cycSum > 0 ? 100.0 * busySum / cycSum : 0.0, busySum / secs / 1e6, good, dropped);
            else
                LOGI("GPU counters: no valid samples (%d dropped)", dropped);
            xoSum = cycSum = busySum = 0; good = dropped = 0;
            windowStart = now;
        }
    }
}

void GpuCounters::close() {
    if (fd_ >= 0) {
        if (cyclesReg_) { kgsl_perfcounter_put p{KGSL_PERFCOUNTER_GROUP_RBBM, kCountableAlwaysCount, {0, 0}}; ioctl(fd_, IOCTL_KGSL_PERFCOUNTER_PUT, &p); }
        if (busyReg_) { kgsl_perfcounter_put p{KGSL_PERFCOUNTER_GROUP_RBBM, kCountableStatusMasked, {0, 0}}; ioctl(fd_, IOCTL_KGSL_PERFCOUNTER_PUT, &p); }
        if (context_) { kgsl_drawctxt_destroy d{}; d.drawctxt_id = context_; ioctl(fd_, IOCTL_KGSL_DRAWCTXT_DESTROY, &d); }
    }
    if (bufferMap_) munmap(bufferMap_, bufferMapSize_);
    if (buffer_ && fd_ >= 0) { kgsl_gpumem_free_id f{}; f.id = buffer_; ioctl(fd_, IOCTL_KGSL_GPUMEM_FREE_ID, &f); }
    if (fd_ >= 0) ::close(fd_);
    fd_ = -1; context_ = buffer_ = timestamp_ = 0; bufferGpuAddr_ = 0; bufferMap_ = nullptr; bufferMapSize_ = 0;
    cyclesReg_ = busyReg_ = 0;
}

bool GpuCounters::start() {
    if (running_.load()) return true;
    if (!open()) { close(); return false; }
    running_ = true;
    thread_ = std::thread([this] { loop(); });
    return true;
}

void GpuCounters::stop() {
    running_ = false;
    if (thread_.joinable()) thread_.join();
    close();
}
