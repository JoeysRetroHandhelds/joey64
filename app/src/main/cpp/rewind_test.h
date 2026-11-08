// EXPERIMENT (rewind's cost, 2026-11-08): N64_REWIND_TEST=<frames> takes the core's state every that many
// frames on the emulation thread, as rewind would, and hands it to a worker thread that XORs it with the
// previous one and deflates that (zlib level 1), the usual way rewind buffers are kept small. Every 5 s it
// logs the snapshot time on the emulation thread and the worker's time and compressed size. Nothing is kept
// beyond the last state; it never loads one.
#pragma once
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <mutex>
#include <thread>
#include <vector>

class RewindTest {
public:
    // [every]: frames between snapshots (0 = off). [serialize]: fills the buffer, false on failure.
    template <class Size, class Ser>
    void frame(int every, Size sizeFn, Ser serFn);
    ~RewindTest();
private:
    void work();
    int count_ = 0;
    std::vector<uint8_t> snap_;
    std::thread worker_;
    std::mutex m_;
    std::condition_variable cv_;
    std::vector<uint8_t> pending_, prev_, xored_, out_;
    bool has_ = false, stop_ = false;
    // stats since the last log
    int snaps_ = 0, dropped_ = 0, packed_ = 0;
    double snapMs_ = 0, snapMax_ = 0, packMs_ = 0;
    uint64_t packedBytes_ = 0;
    size_t rawSize_ = 0;
    std::chrono::steady_clock::time_point last_ = std::chrono::steady_clock::now();
    void log();
};

template <class Size, class Ser>
void RewindTest::frame(int every, Size sizeFn, Ser serFn) {
    if (every <= 0) return;
    if (!worker_.joinable()) worker_ = std::thread([this] { work(); });
    if (++count_ < every) return;
    count_ = 0;
    auto t0 = std::chrono::steady_clock::now();
    size_t n = sizeFn();
    snap_.resize(n);
    bool ok = n > 0 && serFn(snap_.data(), n);
    double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
    if (ok) {
        std::lock_guard<std::mutex> lk(m_);
        rawSize_ = n;
        snaps_++; snapMs_ += ms; if (ms > snapMax_) snapMax_ = ms;
        if (has_) dropped_++;
        else { pending_.swap(snap_); has_ = true; cv_.notify_one(); }
    }
    if (std::chrono::steady_clock::now() - last_ > std::chrono::seconds(5)) log();
}
