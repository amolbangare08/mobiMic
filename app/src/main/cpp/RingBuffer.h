#pragma once

#include <algorithm>
#include <atomic>
#include <cstddef>
#include <cstring>
#include <memory>

namespace mobimic {

/**
 * Lock-free single-producer / single-consumer ring buffer.
 *
 * Producer is the audio callback, consumer is the drain thread. Nothing here
 * allocates, locks or throws after construction, which is what makes it legal
 * to call from the real-time thread.
 *
 * Capacity is rounded up to a power of two so the index wrap is a mask.
 */
template <typename T>
class RingBuffer {
public:
    explicit RingBuffer(size_t minCapacity) {
        size_t cap = 1;
        while (cap < minCapacity) cap <<= 1;
        capacity_ = cap;
        mask_ = cap - 1;
        buf_ = std::make_unique<T[]>(cap);
    }

    /** Producer side. Returns false and writes nothing if the buffer would overflow. */
    bool write(const T* src, size_t count) {
        const size_t w = write_.load(std::memory_order_relaxed);
        const size_t r = read_.load(std::memory_order_acquire);
        if (capacity_ - (w - r) < count) return false;

        const size_t first = std::min(count, capacity_ - (w & mask_));
        std::memcpy(&buf_[w & mask_], src, first * sizeof(T));
        if (count > first) {
            std::memcpy(&buf_[0], src + first, (count - first) * sizeof(T));
        }
        write_.store(w + count, std::memory_order_release);
        return true;
    }

    /** Consumer side. Returns how many items were actually read. */
    size_t read(T* dst, size_t count) {
        const size_t r = read_.load(std::memory_order_relaxed);
        const size_t w = write_.load(std::memory_order_acquire);
        const size_t avail = w - r;
        const size_t n = std::min(count, avail);
        if (n == 0) return 0;

        const size_t first = std::min(n, capacity_ - (r & mask_));
        std::memcpy(dst, &buf_[r & mask_], first * sizeof(T));
        if (n > first) {
            std::memcpy(dst + first, &buf_[0], (n - first) * sizeof(T));
        }
        read_.store(r + n, std::memory_order_release);
        return n;
    }

    size_t available() const {
        return write_.load(std::memory_order_acquire) - read_.load(std::memory_order_acquire);
    }

    size_t capacity() const { return capacity_; }

    void reset() {
        read_.store(0, std::memory_order_relaxed);
        write_.store(0, std::memory_order_relaxed);
    }

private:
    std::unique_ptr<T[]> buf_;
    size_t capacity_ = 0;
    size_t mask_ = 0;
    std::atomic<size_t> write_{0};
    std::atomic<size_t> read_{0};
};

} // namespace mobimic
