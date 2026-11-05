// Joey64 (2026-11-05), EXPERIMENT N64_KGSL_PRIO=<0-15>: give the custom driver's GPU contexts a KGSL priority.
// Turnip's KGSL backend creates its contexts with no priority bits (kgsl_submitqueue_new), so they get the kernel
// default and share a ringbuffer below Android's compositor, which then preempts our GPU work whenever it composes
// on the GPU (idea from hakuX; written here from msm_kgsl.h). Loaded into the driver's namespace with RTLD_GLOBAL,
// so the driver's ioctl() calls land here. 0 is the highest priority; KGSL maps 4 levels per ringbuffer.
#include <android/log.h>
#include <stdarg.h>
#include <stdlib.h>
#include <sys/syscall.h>
#include <unistd.h>
#include "kgsl.h"

__attribute__((visibility("default"))) int ioctl(int fd, int request, ...) {
    va_list ap;
    va_start(ap, request);
    void *arg = va_arg(ap, void *);
    va_end(ap);
    if ((unsigned int)request == (unsigned int)IOCTL_KGSL_DRAWCTXT_CREATE && arg) {
        const char *env = getenv("N64_KGSL_PRIO");
        if (env && *env) {
            struct kgsl_drawctxt_create *req = (struct kgsl_drawctxt_create *)arg;
            unsigned int prio = (unsigned int)atoi(env) & 0xF;
            unsigned int before = req->flags;
            req->flags = (req->flags & ~KGSL_CONTEXT_PRIORITY_MASK) | (prio << KGSL_CONTEXT_PRIORITY_SHIFT);
            long r = syscall(__NR_ioctl, fd, request, arg);
            __android_log_print(ANDROID_LOG_ERROR, "joey64host", "KGSL priority hook: context %u flags %08x -> %08x (%s)",
                                req->drawctxt_id, before, req->flags, r == 0 ? "ok" : "refused");
            return (int)r;
        }
    }
    return (int)syscall(__NR_ioctl, fd, request, arg);
}
