#include "rewind.h"
#include "joeylog.h"
#include <android/log.h>
#include <algorithm>
#include <cstring>
#include <unistd.h>
#include <zlib.h>

// Packed moment: u32 RLE size, then the RLE stream deflated. RLE stream: records of u32 equal words,
// u32 differing words, then those words XORed; after the last record, the XOR of the tail bytes (size % 8).

namespace {
void putU32(std::vector<uint8_t>& v, uint32_t x) { uint8_t b[4]; memcpy(b, &x, 4); v.insert(v.end(), b, b + 4); }

// [rle] = the runs of a ^ b, both [n] bytes.
void encode(const uint8_t* a, const uint8_t* b, size_t n, std::vector<uint8_t>& rle) {
    rle.clear();
    const size_t nw = n / 8;
    size_t i = 0;
    while (i < nw) {
        size_t z = i;
        uint64_t x, y;
        while (z < nw) { memcpy(&x, a + z * 8, 8); memcpy(&y, b + z * 8, 8); if (x != y) break; ++z; }
        size_t l = z;
        while (l < nw) { memcpy(&x, a + l * 8, 8); memcpy(&y, b + l * 8, 8); if (x == y) break; ++l; }
        putU32(rle, (uint32_t)(z - i));
        putU32(rle, (uint32_t)(l - z));
        size_t at = rle.size();
        rle.resize(at + (l - z) * 8);
        for (size_t w = z; w < l; ++w) {
            memcpy(&x, a + w * 8, 8); memcpy(&y, b + w * 8, 8); x ^= y;
            memcpy(rle.data() + at + (w - z) * 8, &x, 8);
        }
        i = l;
    }
    for (size_t t = nw * 8; t < n; ++t) rle.push_back(a[t] ^ b[t]);
}

// XORs the runs in [rle] into [dst] ([n] bytes). False if the stream doesn't fit.
bool decode(const uint8_t* rle, size_t size, uint8_t* dst, size_t n) {
    const size_t nw = n / 8, tail = n - nw * 8;
    size_t p = 0, w = 0;
    while (w < nw) {
        if (p + 8 > size) return false;
        uint32_t z, l;
        memcpy(&z, rle + p, 4); memcpy(&l, rle + p + 4, 4); p += 8;
        w += z;
        if (w + l > nw || p + (size_t)l * 8 > size) return false;
        for (uint32_t k = 0; k < l; ++k, ++w, p += 8) {
            uint64_t x, y;
            memcpy(&x, dst + w * 8, 8); memcpy(&y, rle + p, 8); x ^= y;
            memcpy(dst + w * 8, &x, 8);
        }
    }
    if (p + tail != size) return false;
    for (size_t t = 0; t < tail; ++t) dst[nw * 8 + t] ^= rle[p + t];
    return true;
}
} // namespace

bool Rewind::pack(const uint8_t* older, const uint8_t* newer, size_t n, std::vector<uint8_t>& dst) {
    encode(older, newer, n, rle_);
    uLongf len = compressBound(rle_.size());
    out_.resize(4 + len);
    uint32_t rs = (uint32_t)rle_.size();
    memcpy(out_.data(), &rs, 4);
    if (compress2(out_.data() + 4, &len, rle_.data(), rle_.size(), 1) != Z_OK) return false;
    dst.assign(out_.begin(), out_.begin() + 4 + len);
    return true;
}

bool Rewind::unpack(const std::vector<uint8_t>& packed, uint8_t* dst, size_t n) {
    if (packed.size() < 4) return false;
    uint32_t rs;
    memcpy(&rs, packed.data(), 4);
    rle_.resize(rs);
    uLongf len = rs;
    if (uncompress(rle_.data(), &len, packed.data() + 4, packed.size() - 4) != Z_OK || len != rs) return false;
    return decode(rle_.data(), rs, dst, n);
}

Rewind::~Rewind() {
    { std::lock_guard<std::mutex> lk(m_); stop_ = true; }
    cv_.notify_all();
    if (worker_.joinable()) worker_.join();
}

void Rewind::configure(bool on, int every, int maxMoments) {
    clear();
    on_ = on;
    every_ = every < 1 ? 1 : every;
    max_ = maxMoments < 1 ? 1 : maxMoments;
    const long pages = sysconf(_SC_PHYS_PAGES), page = sysconf(_SC_PAGE_SIZE);
    const uint64_t total = pages > 0 && page > 0 ? (uint64_t)pages * (uint64_t)page : 0;
    capBytes_ = total ? std::min<uint64_t>(total / 8, 1ull << 30) : 256ull << 20;
    capHit_ = false;
    joeyLog(ANDROID_LOG_INFO, "joey64host", "rewind: %s, a moment every %d frames, %d kept, at most %llu MB", on ? "on" : "off", every_, max_,
            (unsigned long long)(capBytes_ >> 20));
}

void Rewind::drain() {
    std::unique_lock<std::mutex> lk(m_);
    idle_.wait(lk, [this] { return (!has_ && !busy_) || !worker_.joinable(); });
}

void Rewind::clear() {
    drain();
    std::lock_guard<std::mutex> lk(m_);
    moments_.clear();
    hasBase_ = false;
    heldBytes_ = 0;
    count_ = 0;
    tick_ = 0;
}

void Rewind::work() {
    std::vector<uint8_t> cur, curProg;
    for (;;) {
        {
            std::unique_lock<std::mutex> lk(m_);
            cv_.wait(lk, [this] { return has_ || stop_; });
            if (stop_) return;
            cur.swap(pending_); curProg.swap(pendingProg_);
            has_ = false; busy_ = true;
        }
        auto t0 = std::chrono::steady_clock::now();
        Moment m;
        bool packed = false;
        if (hasBase_ && base_.size() == cur.size() && pack(base_.data(), cur.data(), cur.size(), m.packed)) {
            // The older moment (base) as the difference from the newer one (cur). Its achievements'
            // progress the same way: a loaded set's is hundreds of KB (SM64's three sets 445 KB) and
            // barely changes, so kept whole 1 min of rewind held 860 MB (Thor 2026-11-08).
            m.progXor = !baseProg_.empty() && baseProg_.size() == curProg.size() &&
                        pack(baseProg_.data(), curProg.data(), curProg.size(), m.progress);
            if (!m.progXor) m.progress = baseProg_;
            packed = true;
        }
        double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
        {
            std::lock_guard<std::mutex> lk(m_);
            if (packed) {
                heldBytes_ += m.packed.size() + m.progress.size();
                statPacked_++; statPackMs_ += ms; statBytes_ += m.packed.size();
                moments_.push_back(std::move(m));
                while ((int)moments_.size() > max_ || (heldBytes_ > capBytes_ && moments_.size() > 1)) {
                    if (heldBytes_ > capBytes_ && (int)moments_.size() <= max_ && !capHit_) {
                        capHit_ = true;
                        joeyLog(ANDROID_LOG_INFO, "joey64host", "rewind: %llu MB reached, keeping %d moments, not %d",
                                (unsigned long long)(capBytes_ >> 20), (int)moments_.size() - 1, max_);
                    }
                    heldBytes_ -= moments_.front().packed.size() + moments_.front().progress.size();
                    moments_.pop_front();
                }
            } else if (hasBase_) {
                moments_.clear(); heldBytes_ = 0; // a size change or a failed pack breaks the chain
            }
            base_.swap(cur); baseProg_.swap(curProg);
            hasBase_ = true;
            count_ = (int)moments_.size();
            busy_ = false;
        }
        idle_.notify_all();
    }
}

bool Rewind::unpackInto(Moment& m) {
    if (!unpack(m.packed, base_.data(), base_.size())) return false;
    if (!m.progXor) { baseProg_.swap(m.progress); return true; }
    return unpack(m.progress, baseProg_.data(), baseProg_.size());
}

void Rewind::logStats() {
    auto now = std::chrono::steady_clock::now();
    if (now - statAt_ < std::chrono::seconds(10)) return;
    std::lock_guard<std::mutex> lk(m_);
    joeyLog(ANDROID_LOG_INFO, "joey64host",
            "rewind: %d moments kept (%.1f MB), last 10 s: %d taken (%.2f ms each), %d skipped (worker busy), packed %d (%.1f ms, %.0f KB each)",
            (int)moments_.size(), heldBytes_ / 1048576.0, statSnaps_, statSnaps_ ? statSnapMs_ / statSnaps_ : 0.0, statSkipped_,
            statPacked_, statPacked_ ? statPackMs_ / statPacked_ : 0.0, statPacked_ ? statBytes_ / 1024.0 / statPacked_ : 0.0);
    statSnaps_ = statSkipped_ = statPacked_ = 0; statSnapMs_ = statPackMs_ = 0; statBytes_ = 0;
    statAt_ = now;
}
