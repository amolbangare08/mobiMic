#pragma once

#include <android/log.h>
#include <cstdint>

#define MM_TAG "mobiMic"
#define MM_LOGI(...) __android_log_print(ANDROID_LOG_INFO,  MM_TAG, __VA_ARGS__)
#define MM_LOGW(...) __android_log_print(ANDROID_LOG_WARN,  MM_TAG, __VA_ARGS__)
#define MM_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, MM_TAG, __VA_ARGS__)

namespace mobimic {

/**
 * Enable flush-to-zero on the calling thread.
 *
 * Filter tails that decay into denormal numbers can cost 10-100x their normal
 * execution time on ARM, which shows up as unexplained XRuns once the DSP chain
 * lands in Phase 3. Call this once at the top of the audio callback.
 */
inline void enableFlushToZeroOnce() {
    static thread_local bool done = false;
    if (done) return;
    done = true;
#if defined(__aarch64__)
    uint64_t fpcr;
    asm volatile("mrs %0, fpcr" : "=r"(fpcr));
    fpcr |= (1ull << 24); // FZ
    asm volatile("msr fpcr, %0" : : "r"(fpcr));
#elif defined(__arm__)
    uint32_t fpscr;
    asm volatile("vmrs %0, fpscr" : "=r"(fpscr));
    fpscr |= (1u << 24); // FZ
    asm volatile("vmsr fpscr, %0" : : "r"(fpscr));
#endif
}

} // namespace mobimic
