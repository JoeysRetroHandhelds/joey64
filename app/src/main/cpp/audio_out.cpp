#include "audio_out.h"
#include <android/log.h>
#include <cstring>
#include <thread>
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "n64host", __VA_ARGS__)

bool AudioOut::open(int sampleRate) {
    cap_ = (size_t)sampleRate / 5 * 2; // ~200 ms stereo
    ring_.assign(cap_, 0);
    r_ = w_ = 0;

    AAudioStreamBuilder* b = nullptr;
    AAudio_createStreamBuilder(&b);
    AAudioStreamBuilder_setSampleRate(b, sampleRate);
    AAudioStreamBuilder_setChannelCount(b, 2);
    AAudioStreamBuilder_setFormat(b, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setPerformanceMode(b, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setDataCallback(b, &AudioOut::cb, this);
    aaudio_result_t res = AAudioStreamBuilder_openStream(b, &stream_);
    AAudioStreamBuilder_delete(b);
    if (res != AAUDIO_OK) { LOGE("AAudio open failed: %d", res); stream_ = nullptr; return false; }
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

void AudioOut::write(const int16_t* frames, size_t frameCount) {
    if (!stream_) return;
    size_t samples = frameCount * 2;
    for (size_t i = 0; i < samples; ++i) {
        size_t w = w_.load(std::memory_order_relaxed);
        size_t next = (w + 1) % cap_;
        int spins = 0;
        while (next == r_.load(std::memory_order_acquire)) { // full: wait for consumer
            if (++spins > 2000) return;                      // ~give up, avoid deadlock on pause
            std::this_thread::sleep_for(std::chrono::microseconds(250));
        }
        ring_[w] = frames[i];
        w_.store(next, std::memory_order_release);
    }
}

aaudio_data_callback_result_t AudioOut::cb(AAudioStream*, void* user, void* data, int32_t frames) {
    auto* self = static_cast<AudioOut*>(user);
    auto* out = static_cast<int16_t*>(data);
    size_t samples = (size_t)frames * 2;
    for (size_t i = 0; i < samples; ++i) {
        size_t r = self->r_.load(std::memory_order_relaxed);
        if (r == self->w_.load(std::memory_order_acquire)) { // underrun → silence
            memset(out + i, 0, (samples - i) * sizeof(int16_t));
            break;
        }
        out[i] = self->ring_[r];
        self->r_.store((r + 1) % self->cap_, std::memory_order_release);
    }
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}
