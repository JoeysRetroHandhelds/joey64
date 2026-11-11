#include "joeylog.h"
#include "audio_out.h"
#include <android/log.h>
#include <algorithm>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <thread>
#define LOGE(...) joeyLog(ANDROID_LOG_ERROR, "joey64host", __VA_ARGS__)

#define LOGI(...) joeyLog(ANDROID_LOG_INFO, "joey64host", __VA_ARGS__)

namespace {
int64_t nowNs() {
    timespec ts; clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000000000LL + ts.tv_nsec;
}
}

bool AudioOut::open(int srcRate) {
    std::lock_guard<std::mutex> lk(streamMutex_);
    // A second open (another game in this process) closes the stream it had first.
    if (stream_) { AAudioStream_requestStop(stream_); AAudioStream_close(stream_); stream_ = nullptr; }
    stalled_ = false;
    srcRate_ = srcRate;
    paused_ = false;
    lost_ = false;
    retryNs_ = 0;
    return openStream();
}

void AudioOut::onError(AAudioStream*, void* user, aaudio_result_t error) {
    LOGE("audio: stream error %d (%s)", error, AAudio_convertResultToText(error));
    if (error == AAUDIO_ERROR_DISCONNECTED) static_cast<AudioOut*>(user)->lost_ = true;
}

void AudioOut::reopenIfLost() {
    if (!lost_ && !retryNs_) return;
    if (retryNs_ && nowNs() < retryNs_) return;
    std::lock_guard<std::mutex> lk(streamMutex_);
    lost_ = false;
    if (stream_) { AAudioStream_requestStop(stream_); AAudioStream_close(stream_); stream_ = nullptr; }
    if (openStream()) {
        retryNs_ = 0;
        LOGI("audio: reopened after the output device changed");
        if (paused_) AAudioStream_requestPause(stream_);
    } else {
        retryNs_ = nowNs() + 1000000000LL;
    }
}

bool AudioOut::openStream() {
    const int srcRate = srcRate_;
    AAudioStreamBuilder* b = nullptr;
    AAudio_createStreamBuilder(&b);
    // No sample rate set: AAudio picks the device's native rate.
    AAudioStreamBuilder_setChannelCount(b, 2);
    AAudioStreamBuilder_setFormat(b, AAUDIO_FORMAT_PCM_FLOAT);
    AAudioStreamBuilder_setPerformanceMode(b, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    // A game's sound, matching the audio focus MainActivity takes (Settings › Audio, Play alongside other apps).
    const char* usage = getenv("N64_AUDIO_USAGE");
    if (!usage || *usage != '0') {
        AAudioStreamBuilder_setUsage(b, AAUDIO_USAGE_GAME);
        AAudioStreamBuilder_setContentType(b, AAUDIO_CONTENT_TYPE_SONIFICATION);
    }
    // Exclusive = AAudio's MMAP path where the device allows it, else AAudio falls back to shared (Flip).
    // Device latency: Duo 14-16 -> 10 ms, Odin 3 14 -> 9 ms. N64_AUDIO_EXCLUSIVE=0 turns it off.
    const char* excl = getenv("N64_AUDIO_EXCLUSIVE");
    if (!excl || *excl != '0') AAudioStreamBuilder_setSharingMode(b, AAUDIO_SHARING_MODE_EXCLUSIVE);
    AAudioStreamBuilder_setDataCallback(b, &AudioOut::cb, this);
    AAudioStreamBuilder_setErrorCallback(b, &AudioOut::onError, this);
    aaudio_result_t res = AAudioStreamBuilder_openStream(b, &stream_);
    AAudioStreamBuilder_delete(b);
    if (res != AAUDIO_OK) { LOGE("AAudio open failed: %d", res); stream_ = nullptr; return false; }

    int dstRate = AAudioStream_getSampleRate(stream_);
    dstRate_ = dstRate;
    baseStep_ = step_ = (double)srcRate / dstRate;
    drcI_ = 0; frames_ = 0;
    // ~20 ms kept queued (stereo samples) to start; the rest of the ring is slack for slow frames.
    // Settings › Audio's Latency: Low starts at 10 ms and stays, Safe starts at 60.
    const int mode = latencyMode_.load();
    const int startMs = mode == 1 ? 10 : mode == 2 ? 60 : kTargetMs;
    grow_ = mode != 1;
    target_ = (size_t)dstRate * startMs / 1000 * 2;
    pos_ = 0.0; prev_[0] = prev_[1] = 0;
    cap_ = (size_t)dstRate / 5 * 2; // ~200 ms stereo
    ring_.assign(cap_, 0);
    r_ = w_ = 0;
    lastW_ = 0; avgWrite_ = -1; excess_ = 0;
    { const char* sm = getenv("N64_AUDIO_SMOOTH"); smoothMode_ = sm && *sm == '1' ? 1 : sm && *sm == '0' ? 0 : -1; }
    smooth_ = smoothMode_ == 1;
    if (smoothMode_ >= 0) LOGI("audio: smoothed audio wait forced %s (N64_AUDIO_SMOOTH)", smooth_ ? "on" : "off");
    // AAudio defaults the buffer to its whole capacity (8 bursts on the Flip and the Portal: 43 and
    // 32 ms of extra latency). Start at 2 bursts; takeStats adds a burst per xrun.
    AAudioStream_setBufferSizeInFrames(stream_, 2 * AAudioStream_getFramesPerBurst(stream_));
    xruns_ = 0;
    LOGI("audio: core %d Hz -> device %d Hz, perf mode %d, sharing %d, burst %d, buffer %d of %d, latency %s (target %d ms)", srcRate, dstRate,
         AAudioStream_getPerformanceMode(stream_), AAudioStream_getSharingMode(stream_), AAudioStream_getFramesPerBurst(stream_),
         AAudioStream_getBufferSizeInFrames(stream_), AAudioStream_getBufferCapacityInFrames(stream_),
         mode == 1 ? "low" : mode == 2 ? "safe" : "auto", startMs);
    AAudioStream_requestStart(stream_);
    return true;
}

void AudioOut::close() {
    std::lock_guard<std::mutex> lk(streamMutex_);
    retryNs_ = 0;
    if (!stream_) return;
    AAudioStream_requestStop(stream_);
    AAudioStream_close(stream_);
    stream_ = nullptr;
}

void AudioOut::setPaused(bool p) {
    std::lock_guard<std::mutex> lk(streamMutex_);
    paused_ = p;
    if (!stream_) return;
    p ? AAudioStream_requestPause(stream_) : AAudioStream_requestStart(stream_);
}

void AudioOut::setPanelRefreshNs(uint64_t ns) {
    if (smoothMode_ != -1) return;
    bool on = ns > 0 && ns < 15000000; // faster than ~66 Hz
    if (on == smooth_) return;
    smooth_ = on;
    lastW_ = w_.load(std::memory_order_relaxed); avgWrite_ = -1; excess_ = 0;
    LOGI("audio: smoothed audio wait %s (panel refresh %.2f ms)", on ? "on" : "off", ns / 1e6);
}

void AudioOut::waitForRoom() {
    reopenIfLost();
    if (!stream_) return;
    // Waits only above target + one frame of audio: where vsync paces the frames (fill near the
    // target via rate control), audio then never delays a frame past its vsync.
    size_t ceiling = target_ + (size_t)dstRate_ / 60 * 2;
    if (smooth_) {
        // Samples written since the last call (one frame's audio), against their running average; a burst raises
        // the ceiling by what it brought above the average, an empty frame lowers it, so frames start evenly.
        size_t w = w_.load(std::memory_order_relaxed);
        double wrote = (double)((w + cap_ - lastW_) % cap_);
        lastW_ = w;
        if (frames_ > 0) {
            avgWrite_ = avgWrite_ < 0 ? wrote : avgWrite_ + 0.02 * (wrote - avgWrite_);
            double lim = (double)cap_ / 4;
            excess_ = std::max(-lim, std::min(lim, 0.97 * excess_ + (wrote - avgWrite_)));
        }
        ceiling = (size_t)std::max(0.0, (double)ceiling + excess_);
    }
    bool waited = false;
    // Sleep for as long as the excess takes to play, then check again (the callback drains in bursts):
    // a few wake-ups per frame. Polling every 250 us was ~47 per frame, ~3.5% of the Duo's CPU samples.
    auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(100); // never deadlock
    for (size_t f; (f = fill()) > ceiling && std::chrono::steady_clock::now() < deadline;) {
        long us = (long)((double)(f - ceiling) * 1e6 / (dstRate_ * 2.0));
        std::this_thread::sleep_for(std::chrono::microseconds(std::max(250L, us)));
        waited = true;
    }
    // Fill right before the frame: target + a frame when audio paces the frames, ~target when vsync does.
    size_t f = fill();
    fillSum_ += f; ++fillCount_;
    // Dynamic rate control: resample up to 1% faster/slower (17 cents) to hold the fill at the
    // target when vsync, not audio, paces the frames: the clocks differ slightly and frames that
    // miss a vsync produce no audio (Retroid Flip menus: ~0.8% of frames). The integral part only
    // learns from moderate errors (not slowdowns). While audio paces (it waited), the correction
    // fades and never pulls the fill down, which would only speed the game up.
    ++frames_;
    double err = std::max(-1.0, std::min(1.0, ((double)f - (double)target_) / (double)target_));
    if (err > -0.1 && err < 0.1) err = 0; // within ~4 ms: the wait loop's own granularity
    if (waited) { drcI_ *= 0.95; err = std::min(err, 0.0); }
    else if (err > -0.75 && err < 0.75) drcI_ = std::max(-0.01, std::min(0.01, drcI_ + 0.0001 * err));
    step_ = baseStep_ * (1.0 + std::max(-0.01, std::min(0.01, 0.005 * err + drcI_)));
}

double AudioOut::outputLatencyMs() {
    if (!stream_) return -1;
    int64_t pos = 0, ns = 0;
    if (AAudioStream_getTimestamp(stream_, CLOCK_MONOTONIC, &pos, &ns) != AAUDIO_OK) return -1;
    timespec ts; clock_gettime(CLOCK_MONOTONIC, &ts);
    int64_t now = ts.tv_sec * 1000000000LL + ts.tv_nsec;
    // The frame written now plays after the ones queued ahead of it.
    int64_t playedNow = pos + (now - ns) * dstRate_ / 1000000000LL;
    return (AAudioStream_getFramesWritten(stream_) - playedNow) * 1000.0 / dstRate_;
}

void AudioOut::takeStats(bool keepingUp, double& fillMs, double& targetMs, int& underruns) {
    if (stream_) {
        int x = AAudioStream_getXRunCount(stream_);
        if (x > xruns_) {
            int32_t size = AAudioStream_getBufferSizeInFrames(stream_) + AAudioStream_getFramesPerBurst(stream_);
            if (size <= AAudioStream_getBufferCapacityInFrames(stream_)) {
                AAudioStream_setBufferSizeInFrames(stream_, size);
                LOGI("audio: %d xruns, buffer now %d frames", x - xruns_, size);
            }
            xruns_ = x;
        }
    }
    auto ms = [this](double samples) { return samples / 2 * 1000.0 / dstRate_; };
    fillMs = fillCount_ ? ms(fillSum_ / fillCount_) : 0;
    fillSum_ = 0; fillCount_ = 0;
    underruns = underruns_.exchange(0);
    size_t step = (size_t)dstRate_ / 100 * 2; // 10 ms
    if (grow_ && underruns && keepingUp && frames_ > 600 && ms((double)(target_ + step)) <= kMaxTargetMs)
        target_ += step;
    targetMs = ms((double)target_);
}

// Returns false if the ring stayed full (paused / stalled consumer).
bool AudioOut::push(float s) {
    size_t w = w_.load(std::memory_order_relaxed);
    size_t next = (w + 1) % cap_;
    int spins = 0;
    while (next == r_.load(std::memory_order_acquire)) { // full: wait for consumer
        // Once a wait gave up, drop samples without waiting until the consumer moves again: a stream that stalls
        // without reporting an error cost ~0.5 s on every write, so the game crawled.
        if (lost_ || stalled_ || ++spins > 2000) { stalled_ = true; return false; } // ~give up, avoid deadlock on pause
        std::this_thread::sleep_for(std::chrono::microseconds(250));
    }
    stalled_ = false;
    ring_[w] = s;
    w_.store(next, std::memory_order_release);
    return true;
}

void AudioOut::takeRates(double secs, double& inHz, double& outHz) {
    inHz = secs > 0 ? srcFrames_.exchange(0) / secs : 0;
    outHz = secs > 0 ? outFrames_.exchange(0) / secs : 0;
}

void AudioOut::write(const int16_t* frames, size_t frameCount) {
    if (!stream_) return;
    srcFrames_.fetch_add((long long)frameCount, std::memory_order_relaxed);
    // Linear interpolation between prev_ and each new source frame.
    for (size_t i = 0; i < frameCount; ++i) {
        const int16_t* cur = frames + i * 2;
        while (pos_ < 1.0) {
            for (int ch = 0; ch < 2; ++ch)
                if (!push((prev_[ch] + (cur[ch] - prev_[ch]) * (float)pos_) * (1.0f / 32768))) return;
            pos_ += step_;
        }
        pos_ -= 1.0;
        prev_[0] = cur[0]; prev_[1] = cur[1];
    }
}

void AudioOut::writeFast(const int16_t* frames, size_t frameCount, double speed, float gain) {
    if (!stream_ || !cap_) return;
    // Steer around the target: up to half again faster when the ring fills, slower when it empties.
    const size_t f = fill();
    fillSum_ += f; ++fillCount_; // the stats line's fill, as waitForRoom keeps it outside fast-forward
    double err = std::max(-1.0, std::min(1.0, ((double)f - (double)target_) / (double)target_));
    // Below 1: slow motion (approved 2026-11-08), its sound slower and lower.
    const double step = baseStep_ * std::max(0.1, speed) * (1.0 + 0.5 * err);
    const float k = gain * (1.0f / 32768);
    for (size_t i = 0; i < frameCount; ++i) {
        const int16_t* cur = frames + i * 2;
        while (pos_ < 1.0) {
            size_t w = w_.load(std::memory_order_relaxed);
            size_t next = (w + 2) % cap_;
            // Full (fewer than 2 free samples): drop the rest of this batch.
            if ((w + 1) % cap_ == r_.load(std::memory_order_acquire) || next == r_.load(std::memory_order_acquire)) {
                prev_[0] = frames[(frameCount - 1) * 2]; prev_[1] = frames[(frameCount - 1) * 2 + 1];
                pos_ = 0; return;
            }
            ring_[w] = (prev_[0] + (cur[0] - prev_[0]) * (float)pos_) * k;
            ring_[(w + 1) % cap_] = (prev_[1] + (cur[1] - prev_[1]) * (float)pos_) * k;
            w_.store(next, std::memory_order_release);
            pos_ += step;
        }
        pos_ -= 1.0;
        prev_[0] = cur[0]; prev_[1] = cur[1];
    }
}

void AudioOut::setMix(int volumePct, bool mute, bool mono) {
    float v = std::max(0, std::min(100, volumePct)) / 100.0f;
    gain_ = mute ? 0.0f : v * v;
    mono_ = mono;
    LOGI("audio: volume %d%%%s%s", volumePct, mute ? ", muted" : "", mono ? ", mono" : "");
}

aaudio_data_callback_result_t AudioOut::cb(AAudioStream*, void* user, void* data, int32_t frames) {
    auto* self = static_cast<AudioOut*>(user);
    auto* out = static_cast<float*>(data);
    size_t samples = (size_t)frames * 2;
    self->outFrames_.fetch_add(frames, std::memory_order_relaxed);
    for (size_t i = 0; i < samples; ++i) {
        size_t r = self->r_.load(std::memory_order_relaxed);
        if (r == self->w_.load(std::memory_order_acquire)) { // underrun → silence
            self->underruns_.fetch_add(1, std::memory_order_relaxed);
            memset(out + i, 0, (samples - i) * sizeof(float));
            break;
        }
        out[i] = self->ring_[r];
        self->r_.store((r + 1) % self->cap_, std::memory_order_release);
    }
    const float g = self->gain_.load(std::memory_order_relaxed);
    if (self->mono_.load(std::memory_order_relaxed)) {
        for (size_t i = 0; i + 1 < samples; i += 2) { float m = (out[i] + out[i + 1]) * 0.5f * g; out[i] = out[i + 1] = m; }
    } else if (g != 1.0f) {
        for (size_t i = 0; i < samples; ++i) out[i] *= g;
    }
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}
