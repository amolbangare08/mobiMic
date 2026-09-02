#pragma once

#include <cmath>
#include <cstddef>
#include <vector>

namespace mobimic::dsp {

/**
 * Iterative radix-2 complex FFT with precomputed twiddles.
 *
 * Small and dependency-free on purpose: the noise suppressor needs a 512-point
 * transform a couple of hundred times a second, which does not justify pulling in
 * a third-party FFT and its build system.
 *
 * All storage is allocated in prepare(); forward() and inverse() are allocation-free.
 */
class Fft {
public:
    void prepare(size_t size) {
        size_ = size;
        cosTable_.resize(size / 2);
        sinTable_.resize(size / 2);
        for (size_t i = 0; i < size / 2; ++i) {
            const double angle = -2.0 * M_PI * static_cast<double>(i) / static_cast<double>(size);
            cosTable_[i] = static_cast<float>(std::cos(angle));
            sinTable_[i] = static_cast<float>(std::sin(angle));
        }
        reverse_.resize(size);
        size_t bits = 0;
        while ((size_t{1} << bits) < size) ++bits;
        for (size_t i = 0; i < size; ++i) {
            size_t r = 0;
            for (size_t b = 0; b < bits; ++b) {
                if (i & (size_t{1} << b)) r |= size_t{1} << (bits - 1 - b);
            }
            reverse_[i] = r;
        }
    }

    size_t size() const { return size_; }

    /** In-place forward transform of interleaved-free split real/imaginary arrays. */
    void forward(float* re, float* im) const { transform(re, im, false); }

    /** In-place inverse transform, scaled by 1/N. */
    void inverse(float* re, float* im) const {
        transform(re, im, true);
        const float scale = 1.0f / static_cast<float>(size_);
        for (size_t i = 0; i < size_; ++i) {
            re[i] *= scale;
            im[i] *= scale;
        }
    }

private:
    void transform(float* re, float* im, bool inverse) const {
        for (size_t i = 0; i < size_; ++i) {
            const size_t j = reverse_[i];
            if (j > i) {
                std::swap(re[i], re[j]);
                std::swap(im[i], im[j]);
            }
        }

        for (size_t len = 2; len <= size_; len <<= 1) {
            const size_t half = len >> 1;
            const size_t step = size_ / len;
            for (size_t i = 0; i < size_; i += len) {
                for (size_t k = 0; k < half; ++k) {
                    const size_t t = k * step;
                    const float wr = cosTable_[t];
                    const float wi = inverse ? -sinTable_[t] : sinTable_[t];
                    const size_t a = i + k;
                    const size_t b = a + half;
                    const float xr = re[b] * wr - im[b] * wi;
                    const float xi = re[b] * wi + im[b] * wr;
                    re[b] = re[a] - xr;
                    im[b] = im[a] - xi;
                    re[a] += xr;
                    im[a] += xi;
                }
            }
        }
    }

    size_t size_ = 0;
    std::vector<float> cosTable_;
    std::vector<float> sinTable_;
    std::vector<size_t> reverse_;
};

} // namespace mobimic::dsp
