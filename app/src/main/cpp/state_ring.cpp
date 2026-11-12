#include "state_ring.h"
#include "joeylog.h"
#define LOGI(...) joeyLog(ANDROID_LOG_INFO, "joey64host", __VA_ARGS__)
#define LOGE(...) joeyLog(ANDROID_LOG_ERROR, "joey64host", __VA_ARGS__)
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <dirent.h>
#include <sys/stat.h>

// The M64+SAVE layout the PC tools read (joey64-data widescreen-finder.py, projscan2.py): RDRAM at 0x1BC, 8 MB,
// stored as host-order 32-bit words.
static constexpr size_t kRdramAt = 0x1BC, kRdramSize = 0x800000;

static float f32(uint32_t w) { float f; memcpy(&f, &w, 4); return f; }

bool stateDrew3d(const uint8_t* state, size_t size) {
    if (!state || size < kRdramAt + 64 || memcmp(state, "M64+SAVE", 8) != 0) return false;
    const size_t n = std::min(kRdramSize, size - kRdramAt) / 4;
    std::vector<uint32_t> w(n);
    memcpy(w.data(), state + kRdramAt, n * 4);
    // Float 4x4 (guPerspectiveF's output): m33 = 0, x/y off-diagonals 0, m23 < 0, m00 and m11 sane, aspect 0.5-3.
    for (size_t i = 0; i + 16 <= n; i++) {
        if (w[i + 15] || w[i + 1] || w[i + 4] || w[i + 3] || w[i + 7] || !w[i] || !w[i + 5] || !w[i + 11]) continue;
        const float m00 = f32(w[i]), m11 = f32(w[i + 5]), m23 = f32(w[i + 11]);
        if (!(m23 < 0 && m23 > -1e4f)) continue;
        if (!(std::fabs(m00) > 0.05f && std::fabs(m00) < 50 && std::fabs(m11) > 0.05f && std::fabs(m11) < 50)) continue;
        const float r = m11 / m00;
        if (r > 0.5f && r < 3) return true;
    }
    // The RSP's fixed form (guPerspective): 16 integer halves, then 16 fraction halves, big-endian halves in each word.
    auto half = [&](size_t base, int k) -> uint32_t { const uint32_t v = w[base + k / 2]; return k % 2 == 0 ? v >> 16 : v & 0xFFFF; };
    for (size_t i = 0; i + 16 <= n; i += 2) {
        double v[16];
        for (int k = 0; k < 16; k++) {
            const int32_t x = (int32_t)((half(i, k) << 16) | half(i + 8, k));
            v[k] = x / 65536.0;
        }
        if (v[15] != 0 || v[1] != 0 || v[4] != 0 || v[3] != 0 || v[7] != 0 || !(v[11] < 0) || v[0] == 0 || v[5] == 0) continue;
        if (std::fabs(v[0]) > 0.05 && std::fabs(v[0]) < 50 && v[5] / v[0] > 0.5 && v[5] / v[0] < 3) return true;
    }
    return false;
}

static void removeOldRing(const std::string& dir) {
    if (DIR* d = opendir(dir.c_str())) {
        while (dirent* e = readdir(d)) {
            const std::string n = e->d_name;
            if ((n.size() > 4 && n.compare(n.size() - 4, 4, ".raw") == 0) || n == "meta.json" || n.find(".tmp") != std::string::npos)
                remove((dir + "/" + n).c_str());
        }
        closedir(d);
    }
}

static void mkdirs(const std::string& dir) {
    for (size_t p = 1; p <= dir.size(); p++)
        if (p == dir.size() || dir[p] == '/') mkdir(dir.substr(0, p).c_str(), 0700);
}

void StateRing::configure(const std::string& dir, int everySec, int keep) {
    finish();
    dir_ = dir;
    every_ = everySec > 0 ? everySec : 60;
    keep_ = keep > 0 ? keep : 5;
    seq_ = 0; counted_ = 0;
    nextAt_ = (uint64_t)(every_ * fps_);
    entries_.clear(); jobs_.clear(); metaPlay_ = 0; quitAt_ = 0;
    if (dir_.empty()) return;
    mkdirs(dir_);
    removeOldRing(dir_);
    LOGI("state ring: on, %s, a state every %d s of play, %d kept", dir_.c_str(), every_, keep_);
}

void StateRing::finish() {
    {
        std::lock_guard<std::mutex> lk(m_);
        if (dir_.empty() && !worker_.joinable()) return;
        stop_ = true;
        metaPlay_ = playSeconds();
        quitAt_ = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::system_clock::now().time_since_epoch()).count();
    }
    cv_.notify_one();
    if (worker_.joinable()) worker_.join();
    if (!dir_.empty()) {
        writeMeta();
        LOGI("state ring: session ended, %.0f s of play, %zu states kept", playSeconds(), entries_.size());
    }
    dir_.clear();
}

void StateRing::work() {
    std::unique_lock<std::mutex> lk(m_);
    for (;;) {
        cv_.wait(lk, [&] { return stop_ || !jobs_.empty(); });
        if (jobs_.empty()) { if (stop_) return; continue; }
        Job j = std::move(jobs_.front()); jobs_.pop_front();
        lk.unlock();
        const bool d3 = stateDrew3d(j.data.data(), j.data.size());
        const std::string path = dir_ + "/" + std::to_string(j.seq % keep_) + ".raw";
        const std::string tmp = path + ".tmp";
        bool ok = false;
        if (FILE* f = fopen(tmp.c_str(), "wb")) {
            ok = fwrite(j.data.data(), 1, j.data.size(), f) == j.data.size();
            ok = fclose(f) == 0 && ok;
        }
        ok = ok && rename(tmp.c_str(), path.c_str()) == 0;
        if (!ok) remove(tmp.c_str());
        const long long now = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::system_clock::now().time_since_epoch()).count();
        LOGI("state ring: %s at %.0f s of play, 3D %s, %s", path.c_str(), j.playAt, d3 ? "yes" : "no", ok ? "written" : "FAILED");
        lk.lock();
        if (ok) {
            for (auto it = entries_.begin(); it != entries_.end();) it = it->path == path ? entries_.erase(it) : it + 1;
            entries_.push_back({path, now, j.playAt, d3});
            while ((int)entries_.size() > keep_) entries_.pop_front();
        }
        lk.unlock();
        writeMeta();
        lk.lock();
    }
}

void StateRing::writeMeta() {
    std::string s;
    {
        std::lock_guard<std::mutex> lk(m_);
        char buf[96];
        snprintf(buf, sizeof buf, "{\"version\":1,\"playSeconds\":%.0f,", metaPlay_);
        s = buf;
        if (quitAt_ > 0) { snprintf(buf, sizeof buf, "\"quitAt\":%lld,", quitAt_); s += buf; }
        s += "\"entries\":[";
        for (size_t k = 0; k < entries_.size(); k++) {
            const Entry& e = entries_[k];
            std::string p;
            for (char c : e.path) { if (c == '"' || c == '\\') p += '\\'; p += c; }
            snprintf(buf, sizeof buf, "\",\"takenAt\":%lld,\"playAt\":%.0f,\"drew3d\":%s}", e.takenAt, e.playAt, e.drew3d ? "true" : "false");
            s += (k ? ",{\"path\":\"" : "{\"path\":\"") + p + buf;
        }
        s += "]}\n";
    }
    const std::string path = dir_ + "/meta.json", tmp = path + ".tmp";
    bool ok = false;
    if (FILE* f = fopen(tmp.c_str(), "wb")) { ok = fwrite(s.data(), 1, s.size(), f) == s.size(); ok = fclose(f) == 0 && ok; }
    if (!(ok && rename(tmp.c_str(), path.c_str()) == 0)) { remove(tmp.c_str()); LOGE("state ring: meta.json not written"); }
}
