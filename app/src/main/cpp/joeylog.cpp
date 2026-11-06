#include "joeylog.h"
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <mutex>
#include <sys/time.h>
#include <unistd.h>

namespace {
std::mutex g_mutex;
FILE* g_file = nullptr;

// Read on every line, not cached: the first lines (LogFiles' header) are logged before MainActivity sets the
// intent's --es env, and a cached answer kept N64_LOG_ERR off for the whole run (Duo bench lost its stats, 2026-11-06)
bool raiseInfo() {
    const char* e = getenv("N64_LOG_ERR");
    return e && *e == '1';
}

char prioChar(int prio) {
    switch (prio) {
        case ANDROID_LOG_VERBOSE: return 'V';
        case ANDROID_LOG_DEBUG: return 'D';
        case ANDROID_LOG_INFO: return 'I';
        case ANDROID_LOG_WARN: return 'W';
        case ANDROID_LOG_ERROR: return 'E';
        case ANDROID_LOG_FATAL: return 'F';
        default: return '?';
    }
}
} // namespace

void joeyLogOpen(const char* path) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_file) { fclose(g_file); g_file = nullptr; }
    if (path) g_file = fopen(path, "a");
}

void joeyLogV(int prio, const char* tag, const char* fmt, va_list ap) {
    char msg[2048];
    va_list copy;
    va_copy(copy, ap);
    vsnprintf(msg, sizeof msg, fmt, copy);
    va_end(copy);
    __android_log_write(prio < ANDROID_LOG_WARN && raiseInfo() ? ANDROID_LOG_ERROR : prio, tag, msg);
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_file) return;
    timeval tv; gettimeofday(&tv, nullptr);
    tm t; localtime_r(&tv.tv_sec, &t);
    size_t n = strlen(msg);
    while (n && (msg[n - 1] == '\n' || msg[n - 1] == '\r')) msg[--n] = 0;   // the core ends its lines with \n
    // Same layout as logcat -v threadtime, so both files read alike
    fprintf(g_file, "%02d-%02d %02d:%02d:%02d.%03d %5d %5d %c %s: %s\n", t.tm_mon + 1, t.tm_mday, t.tm_hour, t.tm_min,
            t.tm_sec, (int)(tv.tv_usec / 1000), getpid(), gettid(), prioChar(prio), tag, msg);
    fflush(g_file);   // a crash must not lose the last lines
}

void joeyLog(int prio, const char* tag, const char* fmt, ...) {
    va_list ap; va_start(ap, fmt);
    joeyLogV(prio, tag, fmt, ap);
    va_end(ap);
}
