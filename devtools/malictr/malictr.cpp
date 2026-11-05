// malictr: log Mali GPU hardware counters from the adb shell (Joey64 master list item 18, 2026-11-05).
// Uses Arm's libGPUCounters 2.5.1 (MIT). No root needed: /dev/mali0 is 0666 on the Flip.
// Usage: malictr [interval_ms=100] [seconds=0 (forever)] [stop_file]
// One tab-separated line per interval:
//   epoch_ms  clock_MHz  gpu_busy%  gpu_active_Mcy  frag_active_Mcy  nonfrag_active_Mcy  tiler_active_Mcy  ext_rd_MB  ext_wr_MB
// gpu_busy% = GPU active cycles / (devfreq clock x interval): how much of the time the GPU had work.
// frag/nonfrag are summed over the shader cores (2 on the Mali-G52), so they can exceed gpu_active.
#include <hwcpipe/gpu.hpp>
#include <hwcpipe/sampler.hpp>

#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <thread>
#include <unistd.h>

static long readClockMHz() {
    FILE* f = fopen("/sys/class/devfreq/60000000.gpu/cur_freq", "r");
    if (!f) return 0;
    long hz = 0;
    if (fscanf(f, "%ld", &hz) != 1) hz = 0;
    fclose(f);
    return hz / 1000000;
}

static double value(hwcpipe::sampler<>& s, hwcpipe_counter c) {
    hwcpipe::counter_sample v;
    if (s.get_counter_value(c, v)) return -1;
    return v.type == hwcpipe::counter_sample::type::uint64 ? double(v.value.uint64) : v.value.float64;
}

int main(int argc, char** argv) {
    int intervalMs = argc > 1 ? atoi(argv[1]) : 100;
    int seconds = argc > 2 ? atoi(argv[2]) : 0;
    const char* stopFile = argc > 3 ? argv[3] : nullptr;
    if (intervalMs < 10) intervalMs = 10;

    auto gpu = hwcpipe::gpu(0);
    if (!gpu) { fprintf(stderr, "malictr: no Mali GPU at /dev/mali0\n"); return 1; }
    auto config = hwcpipe::sampler_config(gpu);
    const hwcpipe_counter counters[] = {MaliGPUActiveCy, MaliFragActiveCy, MaliNonFragActiveCy, MaliTilerActiveCy,
                                        MaliExtBusRdBy, MaliExtBusWrBy};
    for (auto c : counters)
        if (config.add_counter(c)) { fprintf(stderr, "malictr: counter %d not supported\n", int(c)); return 1; }
    auto sampler = hwcpipe::sampler<>(config);
    if (auto ec = sampler.start_sampling()) { fprintf(stderr, "malictr: %s\n", ec.message().c_str()); return 1; }

    using clk = std::chrono::steady_clock;
    auto start = clk::now();
    auto next = start;
    auto last = start;
    printf("epoch_ms\tclock_MHz\tgpu_busy%%\tgpu_active_Mcy\tfrag_Mcy\tnonfrag_Mcy\ttiler_Mcy\text_rd_MB\text_wr_MB\n");
    fflush(stdout);
    for (;;) {
        next += std::chrono::milliseconds(intervalMs);
        std::this_thread::sleep_until(next);
        if (auto ec = sampler.sample_now()) { fprintf(stderr, "malictr: %s\n", ec.message().c_str()); continue; }
        auto now = clk::now();
        double ms = std::chrono::duration<double, std::milli>(now - last).count();
        last = now;
        long mhz = readClockMHz();
        double active = value(sampler, MaliGPUActiveCy);
        double busy = mhz > 0 ? 100.0 * active / (mhz * 1000.0 * ms) : -1;
        long long epoch = std::chrono::duration_cast<std::chrono::milliseconds>(
                              std::chrono::system_clock::now().time_since_epoch()).count();
        printf("%lld\t%ld\t%.1f\t%.2f\t%.2f\t%.2f\t%.2f\t%.2f\t%.2f\n", epoch, mhz, busy, active / 1e6,
               value(sampler, MaliFragActiveCy) / 1e6, value(sampler, MaliNonFragActiveCy) / 1e6,
               value(sampler, MaliTilerActiveCy) / 1e6, value(sampler, MaliExtBusRdBy) / 1e6,
               value(sampler, MaliExtBusWrBy) / 1e6);
        fflush(stdout);
        if (seconds > 0 && now - start >= std::chrono::seconds(seconds)) break;
        if (stopFile && access(stopFile, F_OK) == 0) break;
    }
    sampler.stop_sampling();
    return 0;
}
