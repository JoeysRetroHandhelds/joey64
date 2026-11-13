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
bool isDigit(char c) { return c >= '0' && c <= '9'; }
bool isWord(char c) { return isDigit(c) || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_'; }

bool notPublic(const int b[4]) {
    return b[0] == 0 || b[0] == 10 || b[0] == 127 || (b[0] == 172 && b[1] >= 16 && b[1] <= 31) ||
           (b[0] == 192 && b[1] == 168) || (b[0] == 169 && b[1] == 254) || (b[0] == 100 && b[1] >= 64 && b[1] <= 127);
}

// [in] with every public IPv4 address as "<public address>", into [out] (Kotlin's LogMask.addresses, same rules):
// Share logs sends the session logs, and the router's outside address (upnp.cpp) or a room's host address (the
// core's "Netplay: connected to") would go with them. Addresses on this Wi-Fi or the carrier's network stay.
void maskAddresses(const char* in, char* out, size_t cap) {
    size_t o = 0;
    for (size_t i = 0; in[i] && o + 1 < cap;) {
        // not right after a letter, digit or _ (a version such as the Adreno compiler's "E031.41.03.44"), and no octet
        // with a leading zero (addresses are never written that way)
        bool start = isDigit(in[i]) && !(i > 0 && (isWord(in[i - 1]))) && !(i > 1 && in[i - 1] == '.' && isDigit(in[i - 2]));
        if (start) {
            int b[4]; size_t j = i; bool ok = true;
            for (int k = 0; k < 4 && ok; ++k) {
                int n = 0, d = 0;
                while (isDigit(in[j]) && d < 4) { n = n * 10 + (in[j] - '0'); ++j; ++d; }
                if (d == 0 || d > 3 || n > 255 || (d > 1 && in[j - d] == '0')) ok = false;
                b[k] = n;
                if (ok && k < 3) { if (in[j] == '.') ++j; else ok = false; }
            }
            if (ok && !isDigit(in[j]) && !(in[j] == '.' && isDigit(in[j + 1])) && !notPublic(b)) {
                static const char kMask[] = "<public address>";
                for (const char* m = kMask; *m && o + 1 < cap; ++m) out[o++] = *m;
                i = j;
                continue;
            }
            // Not a public address: copy the whole run of digits so its tail isn't read as a new start.
            while (isDigit(in[i]) && o + 1 < cap) out[o++] = in[i++];
            continue;
        }
        out[o++] = in[i++];
    }
    out[o] = 0;
}
} // namespace

void joeyLogOpen(const char* path) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_file) { fclose(g_file); g_file = nullptr; }
    if (path) g_file = fopen(path, "a");
}

void joeyLogV(int prio, const char* tag, const char* fmt, va_list ap) {
    char raw[2048];
    va_list copy;
    va_copy(copy, ap);
    vsnprintf(raw, sizeof raw, fmt, copy);
    va_end(copy);
    char msg[2048 + 64];
    maskAddresses(raw, msg, sizeof msg);
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
