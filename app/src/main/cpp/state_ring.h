// State ring for the state-request popup (Joey 2026-11-12: Joey64 asks a player for a state of a game the wanted
// list names; joey64-notes queue.md "Ask players for a state of games we need data for"). On only when the game
// screen sets N64_STATE_RING=<dir> (MainActivity, for a game on the wanted list; dev: --es env).
// While the game runs at normal play (not paused, fast-forward, rewind, a benchmark or a state load), the emulation
// thread counts play time and, every [every] counted seconds, takes the core's state; a worker writes it to
// <dir>/<n>.raw (the core's M64+SAVE data, as N64_STATE loads and the PC finders read), marks whether its RDRAM holds a
// perspective matrix (a 3D scene: title screens, menus and logos drawn flat have none), keeps the newest [keep], and
// rewrites <dir>/meta.json: {"version":1,"playSeconds":N,"quitAt":ms,"entries":[{"path","takenAt","playAt","drew3d"}, ...]},
// oldest first (path absolute; takenAt and quitAt epoch ms; quitAt only once the session has ended, written by finish()). The launcher reads meta.json after the game has quit. A new session starts the ring over.
#pragma once
#include <condition_variable>
#include <cstddef>
#include <cstdint>
#include <deque>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

/** True when a core state's RDRAM holds a perspective projection matrix (float or the RSP's 16.16 fixed form). */
bool stateDrew3d(const uint8_t* state, size_t size);

class StateRing {
public:
    ~StateRing() { finish(); }
    /** dir empty = off. Removes the dir's old ring. */
    void configure(const std::string& dir, int everySec, int keep);
    bool on() const { return !dir_.empty(); }
    /** Emulation thread, after each frame run. [counted]: a frame of normal play. */
    template <class Size, class Ser>
    void frame(bool counted, double fps, Size sizeFn, Ser serFn);
    /** End of the session: the last meta.json written, the worker stopped. */
    void finish();
    double playSeconds() const { return fps_ > 0 ? counted_ / fps_ : 0; }
private:
    struct Entry { std::string path; long long takenAt; double playAt; bool drew3d; };
    void work();
    void writeMeta();
    std::string dir_;
    int every_ = 60, keep_ = 5, seq_ = 0;
    double fps_ = 60;
    uint64_t counted_ = 0, nextAt_ = 0;
    std::mutex m_;
    std::condition_variable cv_;
    std::thread worker_;
    bool stop_ = false;
    struct Job { std::vector<uint8_t> data; int seq; double playAt; };
    std::deque<Job> jobs_;
    std::deque<Entry> entries_;   // worker-owned (under m_)
    double metaPlay_ = 0;         // play seconds the next meta.json says (under m_)
    long long quitAt_ = 0;        // epoch ms the session ended, 0 while it runs (under m_)
};

template <class Size, class Ser>
void StateRing::frame(bool counted, double fps, Size sizeFn, Ser serFn) {
    if (dir_.empty()) return;
    if (fps > 0) fps_ = fps;
    if (!counted) return;
    ++counted_;
    if (counted_ < nextAt_) return;
    nextAt_ = counted_ + (uint64_t)(every_ * fps_);
    Job j;
    j.data.resize(sizeFn());
    if (j.data.empty() || !serFn(j.data.data(), j.data.size())) return;
    j.seq = seq_++;
    j.playAt = playSeconds();
    {
        std::lock_guard<std::mutex> lk(m_);
        metaPlay_ = j.playAt;
        if (jobs_.size() >= 2) jobs_.pop_front(); // the worker is behind (slow storage): keep the newest
        jobs_.push_back(std::move(j));
        if (!worker_.joinable()) { stop_ = false; worker_ = std::thread([this] { work(); }); }
    }
    cv_.notify_one();
}
