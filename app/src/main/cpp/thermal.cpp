#include "joeylog.h"
#include "thermal.h"

#include <android/log.h>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <dlfcn.h>

#define LOGI(...) joeyLog(ANDROID_LOG_INFO, "joey64host", __VA_ARGS__)

namespace {
struct AThermalManager;
using AcquireFn = AThermalManager* (*)();
using StatusFn = int (*)(AThermalManager*);
using HeadroomFn = float (*)(AThermalManager*, int);

constexpr int kMaxSpeed = 4;

struct State {
    bool tried = false;
    AThermalManager* mgr = nullptr;
    StatusFn status = nullptr;
    HeadroomFn headroom = nullptr;
    int lastStatus = -1;
    float lastHeadroom = NAN;
    std::chrono::steady_clock::time_point lastRead{};
    bool governor = false;
    std::atomic<int> speed{kMaxSpeed};
};
State s;

void init() {
    s.tried = true;
    s.governor = getenv("N64_THERMAL_GOV") && *getenv("N64_THERMAL_GOV") == '1';
    void* lib = dlopen("libandroid.so", RTLD_NOW | RTLD_LOCAL);
    if (!lib) return;
    auto acquire = reinterpret_cast<AcquireFn>(dlsym(lib, "AThermal_acquireManager"));
    s.status = reinterpret_cast<StatusFn>(dlsym(lib, "AThermal_getCurrentThermalStatus"));
    s.headroom = reinterpret_cast<HeadroomFn>(dlsym(lib, "AThermal_getThermalHeadroom"));
    if (acquire) s.mgr = acquire();
    if (!s.mgr) s.status = nullptr, s.headroom = nullptr;
    LOGI("thermal: status %s, headroom %s, governor %s", s.status ? "yes" : "no", s.headroom ? "yes" : "no",
         s.governor ? "on" : "off");
}

int targetSpeed(int status, float headroom) {
    if (status >= 2 || (!std::isnan(headroom) && headroom >= 0.9f)) return 2;
    if (!std::isnan(headroom) && headroom >= 0.8f) return 3;
    return kMaxSpeed;
}
} // namespace

namespace Thermal {
void update() {
    if (!s.tried) init();
    if (!s.status) return;
    auto now = std::chrono::steady_clock::now();
    if (now - s.lastRead < std::chrono::seconds(1)) return;
    s.lastRead = now;
    s.lastStatus = s.status(s.mgr);
    s.lastHeadroom = s.headroom ? s.headroom(s.mgr, 10) : NAN;
    if (!s.governor) return;
    int cur = s.speed.load(), want = targetSpeed(s.lastStatus, s.lastHeadroom);
    int next = cur;
    if (want < cur) next = want; // heating: drop straight to the level asked for
    else if (want > cur && s.lastStatus < 2 && !std::isnan(s.lastHeadroom) && s.lastHeadroom < 0.7f)
        next = cur + 1;          // cooled well below the thresholds: one step back up
    if (next != cur) {
        s.speed = next;
        LOGI("thermal governor: fast-forward %dx -> %dx (status %d, headroom %.2f)", cur, next, s.lastStatus,
             s.lastHeadroom);
    }
}

std::string text() {
    if (!s.status) return "";
    char buf[80];
    int n = snprintf(buf, sizeof buf, ", thermal %d", s.lastStatus);
    if (s.headroom) n += snprintf(buf + n, sizeof buf - n, ", headroom %.2f", s.lastHeadroom);
    if (s.governor) snprintf(buf + n, sizeof buf - n, ", ff %dx", s.speed.load());
    return buf;
}

int fastForwardSpeed() { return s.speed.load(); }
} // namespace Thermal
