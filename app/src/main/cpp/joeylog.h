#pragma once
// Every host and core log line goes to logcat and to the session log file (files/logs/<session>-app.log, opened by
// LogFiles.kt through NativeBridge.openLog). The file is what a tester sends: some devices' logd keeps nothing below
// warning (the Retroid Duo dropped every info line, stats included, 2026-11-06), so logcat alone loses them.
// N64_LOG_ERR=1 still raises info lines to error in logcat, for reading them over adb on such devices.
#include <android/log.h>
#include <cstdarg>

void joeyLogOpen(const char* path);   // appends; nullptr closes
void joeyLog(int prio, const char* tag, const char* fmt, ...) __attribute__((format(printf, 3, 4)));
void joeyLogV(int prio, const char* tag, const char* fmt, va_list ap);
// For a fatal signal handler: one finished line (ending in \n) straight to the session file and logcat, no lock, no
// allocation, no stdio (async-signal-safe as far as write() and __android_log_write go).
void joeyLogRaw(const char* line);
