#include "rewind_test.h"
#include "joeylog.h"
#include <android/log.h>
#include <cstdarg>
#include <zlib.h>

void RewindTest::work() {
    std::vector<uint8_t> cur;
    for (;;) {
        {
            std::unique_lock<std::mutex> lk(m_);
            cv_.wait(lk, [this] { return has_ || stop_; });
            if (stop_) return;
            cur.swap(pending_);
            has_ = false;
        }
        auto t0 = std::chrono::steady_clock::now();
        size_t n = cur.size();
        uLongf outLen = 0;
        if (prev_.size() == n) {
            xored_.resize(n);
            for (size_t i = 0; i < n; ++i) xored_[i] = cur[i] ^ prev_[i];
            outLen = compressBound(n);
            out_.resize(outLen);
            if (compress2(out_.data(), &outLen, xored_.data(), n, 1) != Z_OK) outLen = 0;
        }
        prev_.swap(cur);
        double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
        if (outLen) {
            std::lock_guard<std::mutex> lk(m_);
            packed_++; packMs_ += ms; packedBytes_ += outLen;
        }
    }
}

void RewindTest::log() {
    std::lock_guard<std::mutex> lk(m_);
    joeyLog(ANDROID_LOG_INFO, "joey64host",
            "rewind test: %d snapshots (%d not packed: worker busy), snapshot %.2f ms mean, %.2f max, %zu bytes raw; "
            "packed %d, %.1f ms each, %.0f KB each (xor + deflate 1)",
            snaps_, dropped_, snaps_ ? snapMs_ / snaps_ : 0.0, snapMax_, rawSize_, packed_,
            packed_ ? packMs_ / packed_ : 0.0, packed_ ? packedBytes_ / 1024.0 / packed_ : 0.0);
    snaps_ = dropped_ = packed_ = 0; snapMs_ = snapMax_ = packMs_ = 0; packedBytes_ = 0;
    last_ = std::chrono::steady_clock::now();
}

RewindTest::~RewindTest() {
    { std::lock_guard<std::mutex> lk(m_); stop_ = true; }
    cv_.notify_all();
    if (worker_.joinable()) worker_.join();
}
