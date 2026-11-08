// Rewind (approved Slow motion and Rewind mockup, 2026-11-08; Controls › Hotkeys › Rewind).
// While on, the emulation thread takes the core's state every [every] frames (3 ms on the Thor since core
// state-return 92e7fba) and hands it to a worker, which keeps the newest state whole ("base") and, for each
// older moment, the difference to the next one: the XOR of the two, as runs of equal 8-byte words and the
// words that differ, deflated at level 1. Going back undoes the newest difference into base and loads it.
// Each moment also keeps the achievements' progress, so they carry on from where rewind stops.
#pragma once
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <deque>
#include <mutex>
#include <thread>
#include <vector>

class Rewind {
public:
    ~Rewind();
    /** Recording on or off, a moment every [every] frames, at most [maxMoments] kept. Starts over. */
    void configure(bool on, int every, int maxMoments);
    bool on() const { return on_; }
    int every() const { return every_; }
    /** Emulation thread, after each frame while not rewinding: every [every] frames, a moment. */
    template <class Size, class Ser, class Progress>
    void record(Size sizeFn, Ser serFn, Progress progressFn);
    /** Emulation thread: back one moment, loaded with [unser](data, size, progress, progressSize). False at the oldest. */
    template <class Unser>
    bool stepBack(Unser unser);
    /** Forgets every moment (a state was loaded, the game reset). */
    void clear();
    /** Moments that can still be gone back to. */
    int moments() const { return count_.load(); }
    int maxMoments() const { return max_; }
    /** Emulation thread: the achievements' progress of the moment last gone back to. */
    const std::vector<uint8_t>& baseProgress() const { return baseProg_; }
private:
    // progress: the moment's achievements' progress, whole or (progXor) packed as the XOR to the next newer one's.
    struct Moment { std::vector<uint8_t> packed; std::vector<uint8_t> progress; bool progXor = false; };
    void work();
    void drain();
    /** base_ and baseProg_ (the newer moment) become [m]. */
    bool unpackInto(Moment& m);
    /** [dst] = [older] as the deflated runs of older ^ newer ([n] bytes each). */
    bool pack(const uint8_t* older, const uint8_t* newer, size_t n, std::vector<uint8_t>& dst);
    /** XORs a [pack]ed difference into [dst]. */
    bool unpack(const std::vector<uint8_t>& packed, uint8_t* dst, size_t n);
    bool on_ = false;
    int every_ = 2, max_ = 1800, tick_ = 0;
    std::atomic<int> count_{0};
    // The worker's (and, once drained, the emulation thread's).
    std::vector<uint8_t> base_, baseProg_;
    bool hasBase_ = false;
    std::deque<Moment> moments_;
    // Handed from the emulation thread to the worker.
    std::vector<uint8_t> snap_, snapProg_, pending_, pendingProg_, spare_;
    std::vector<uint8_t> rle_, out_;
    bool has_ = false, busy_ = false, stop_ = false;
    std::thread worker_;
    std::mutex m_;
    std::condition_variable cv_, idle_;
    // Stats, logged every 10 s while recording.
    int statSnaps_ = 0, statSkipped_ = 0, statPacked_ = 0;
    double statSnapMs_ = 0, statPackMs_ = 0;
    uint64_t statBytes_ = 0, heldBytes_ = 0;
    std::chrono::steady_clock::time_point statAt_ = std::chrono::steady_clock::now();
    void logStats();
};

template <class Size, class Ser, class Progress>
void Rewind::record(Size sizeFn, Ser serFn, Progress progressFn) {
    if (!on_) return;
    if (++tick_ < every_) return;
    tick_ = 0;
    {
        std::lock_guard<std::mutex> lk(m_);
        if (has_) { statSkipped_++; return; } // the worker is behind: this moment is skipped
    }
    auto t0 = std::chrono::steady_clock::now();
    size_t n = sizeFn();
    snap_.resize(n);
    if (n == 0 || !serFn(snap_.data(), n)) return;
    progressFn(snapProg_);
    double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
    {
        std::lock_guard<std::mutex> lk(m_);
        statSnaps_++; statSnapMs_ += ms;
        pending_.swap(snap_); pendingProg_.swap(snapProg_);
        has_ = true;
        if (!worker_.joinable()) worker_ = std::thread([this] { work(); });
    }
    cv_.notify_one();
    logStats();
}

template <class Unser>
bool Rewind::stepBack(Unser unser) {
    drain();
    std::lock_guard<std::mutex> lk(m_);
    if (moments_.empty() || !hasBase_) return false;
    const size_t held = moments_.back().packed.size() + moments_.back().progress.size();
    if (!unpackInto(moments_.back())) { moments_.clear(); count_ = 0; heldBytes_ = 0; return false; }
    heldBytes_ -= held;
    moments_.pop_back();
    count_ = (int)moments_.size();
    tick_ = 0;
    unser(base_.data(), base_.size(), baseProg_.data(), baseProg_.size());
    return true;
}
