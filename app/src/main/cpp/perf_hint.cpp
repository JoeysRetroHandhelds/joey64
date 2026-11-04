#include "perf_hint.h"
#include <android/log.h>
#include <cstdlib>
#include <dlfcn.h>
#include <time.h>
#include <unistd.h>

#define LOGI(...) __android_log_print(getenv("N64_LOG_ERR") && *getenv("N64_LOG_ERR") == '1' ? ANDROID_LOG_ERROR : ANDROID_LOG_INFO, "n64host", __VA_ARGS__)

struct APerformanceHintManager;

namespace {
// Half a 60 Hz frame: the work should finish well before the next vsync
constexpr int64_t kTargetNs = 1000000000LL / 60 / 2;

struct Api {
    APerformanceHintManager* (*getManager)();
    APerformanceHintSession* (*createSession)(APerformanceHintManager*, const int32_t*, size_t, int64_t);
    int (*reportActual)(APerformanceHintSession*, int64_t);
    void (*closeSession)(APerformanceHintSession*);

    bool load() {
        void* lib = dlopen("libandroid.so", RTLD_NOW);
        if (!lib) return false;
        getManager = (decltype(getManager))dlsym(lib, "APerformanceHint_getManager");
        createSession = (decltype(createSession))dlsym(lib, "APerformanceHint_createSession");
        reportActual = (decltype(reportActual))dlsym(lib, "APerformanceHint_reportActualWorkDuration");
        closeSession = (decltype(closeSession))dlsym(lib, "APerformanceHint_closeSession");
        return getManager && createSession && reportActual && closeSession;
    }
};

const Api* api() {
    static Api a{};
    static const bool ok = a.load();
    return ok ? &a : nullptr;
}

int64_t threadCpuNs() {
    timespec ts;
    if (clock_gettime(CLOCK_THREAD_CPUTIME_ID, &ts) != 0) return -1;
    return ts.tv_sec * 1000000000LL + ts.tv_nsec;
}
}

PerfHint::~PerfHint() {
    if (session_) api()->closeSession(session_);
}

void PerfHint::reportFrame() {
    if (failed_) return;
    if (!session_) {
        const Api* a = api();
        APerformanceHintManager* m = a ? a->getManager() : nullptr;
        const int32_t tid = gettid();
        session_ = m ? a->createSession(m, &tid, 1, kTargetNs) : nullptr;
        if (!session_) { LOGI("performance hint: not available"); failed_ = true; return; }
        LOGI("performance hint: session for thread %d, target %lld us", tid, (long long)(kTargetNs / 1000));
    }
    const int64_t now = threadCpuNs();
    if (lastCpuNs_ >= 0 && now > lastCpuNs_) api()->reportActual(session_, now - lastCpuNs_);
    lastCpuNs_ = now;
}
