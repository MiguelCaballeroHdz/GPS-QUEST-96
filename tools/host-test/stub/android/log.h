#pragma once
// Stub de <android/log.h> para compilar el core en Windows (host test).
#include <cstdarg>
#include <cstdio>

#define ANDROID_LOG_DEBUG 3
#define ANDROID_LOG_INFO 4
#define ANDROID_LOG_WARN 5
#define ANDROID_LOG_ERROR 6

inline int __android_log_print(int, const char* tag, const char* fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    std::printf("[%s] ", tag);
    std::vfprintf(stdout, fmt, ap);
    std::printf("\n");
    va_end(ap);
    return 0;
}
