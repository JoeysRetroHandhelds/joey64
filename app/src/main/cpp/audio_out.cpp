#include "audio_out.h"
#include <android/log.h>
#include <algorithm>
#include <cstring>
#include <ctime>
#include <thread>
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "n64host", __VA_ARGS__)

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "n64host", __VA_ARGS__)

bool AudioOut::open(int srcRate) {
    AAudioStreamBuilder* b = nullptr;
    AAudio_createStreamBuilder(&b);
    // No sample rate set: AAudio picks the device's native rate.
    AAudioStreamBuilder_setChannelCount(b, 2);
    AAudioStreamBuilder_setFormat(b, AAUDIO_FORMAT_PCM_FLOAT);
    AAudioStreamBuilder_setPerformanceMode(b, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setDataCallback(b, &AudioOut::cb, this);
    aaudio_result_t res = AAudioStreamBuilder_openStream(b, &stream_);
    AAudioStreamBuilder_delete(b);
    if (res != AAUDIO_OK) { LOGE("AAudio open failed: %d", res); stream_ = nullptr; return false; }

    int dstRate = AAudioStream_getSampleRate(stream_);
    dstRate_ = dstRate;
    baseStep_ = step_ = (double)srcRate / dstRate;
    drcI_ = 0; frames_ = 0;
    // ~20 ms kept queued (stereo samples) to start; the rest of the ring is slack for slow frames.
    target_ = (size_t)dstRate * kTargetMs / 1000 * 2;
    pos_ = 0.0; prev_[0] = prev_[1] = 0;
    cap_ = (size_t)dstRate / 5 * 2; // ~200 ms stereo
    ring_.assign(cap_, 0);
    r_ = w_ = 0;
    // AAudio defaults the buffer to its whole capacity (8 bursts on the Flip and the Portal: 43 and
    // 32 ms of extra latency). Start at 2 bursts; takeStats adds a burst per xrun.
    AAudioStream_setBufferSizeInFrames(stream_, 2 * AAudioStream_getFramesPerBurst(stream_));
    xruns_ = 0;
    LOGI("audio: core %d Hz -> device %d Hz, perf mode %d, burst %d, buffer %d of %d", srcRate, dstRate,
         AAudioStream_getPerformanceMode(stream_), AAudioStream_getFramesPerBurst(stream_),
         AAudioStream_getBufferSizeInFrames(stream_), AAudioStream_getBufferCapacityInFrames(stream_));
    AAudioStream_requestStart(stream_);
    return true;
}

void AudioOut::close() {
    if (!stream_) return;
    AAudioStream_requestStop(stream_);
    AAudioStream_close(stream_);
    stream_ = nullptr;
}

void AudioOut::setPaused(bool p) {
    if (!stream_) return;
    p ? AAudioStream_requestPause(stream_) : AAudioStream_requestStart(stream_);
}

void AudioOut::waitForRoom() {
    if (!stream_) return;
    // Waits only above target + one frame of audio: where vsync paces the frames (fill near the
    // target via rate control), audio then never delays a frame past its vsync.
    size_t ceiling = target_ + (size_t)dstRate_ / 60 * 2;
    bool waited = false;
    for (int spins = 0; fill() > ceiling && spins < 400; ++spins) { // <= ~100 ms, never deadlock
        std::this_thread::sleep_for(std::chrono::microseconds(250));
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
    if (underruns && keepingUp && frames_ > 600 && ms((double)(target_ + step)) <= kMaxTargetMs)
        target_ += step;
    targetMs = ms((double)target_);
}

// Returns false if the ring stayed full (paused / stalled consumer).
bool AudioOut::push(float s) {
    size_t w = w_.load(std::memory_order_relaxed);
    size_t next = (w + 1) % cap_;
    int spins = 0;
    while (next == r_.load(std::memory_order_acquire)) { // full: wait for consumer
        if (++spins > 2000) return false;                // ~give up, avoid deadlock on pause
        std::this_thread::sleep_for(std::chrono::microseconds(250));
    }
    ring_[w] = s;
    w_.store(next, std::memory_order_release);
    return true;
}

void AudioOut::write(const int16_t* frames, size_t frameCount) {
    if (!stream_) return;
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

aaudio_data_callback_result_t AudioOut::cb(AAudioStream*, void* user, void* data, int32_t frames) {
    auto* self = static_cast<AudioOut*>(user);
    auto* out = static_cast<float*>(data);
    size_t samples = (size_t)frames * 2;
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
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}
