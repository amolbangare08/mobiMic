#include "SpectralNoiseSuppressor.h"

#include <algorithm>
#include <cmath>

namespace mobimic::dsp {

namespace {

/** Frames between minimum-tracker resets. ~1.3 s at 48 kHz with a 256 hop. */
constexpr int kMinTrackerFrames = 240;

/** Noise floor is biased up: the tracked minimum systematically underestimates it. */
constexpr float kNoiseBias = 1.6f;

/** Decision-directed smoothing. High values trade responsiveness for less musical noise. */
constexpr float kAlphaDd = 0.98f;

/** Power smoothing across frames before the minimum tracker sees it. */
constexpr float kPowerSmoothing = 0.85f;

/** Never attenuate more than this, so the residual noise stays natural instead of gurgling. */
constexpr float kGainFloor = 0.08f; // about -22 dB

constexpr float kEpsilon = 1.0e-12f;

} // namespace

void SpectralNoiseSuppressor::prepare(float /*sampleRate*/, int /*maxBlockFrames*/) {
    fft_.prepare(kWindow);

    // Square-root Hann for both analysis and synthesis: their product is a Hann
    // window, which sums to exactly 1.0 at 50% overlap, so unity gain is unity.
    window_.resize(kWindow);
    for (size_t i = 0; i < kWindow; ++i) {
        const float hann = 0.5f * (1.0f - std::cos(2.0f * static_cast<float>(M_PI) *
                                                   static_cast<float>(i) / static_cast<float>(kWindow)));
        window_[i] = std::sqrt(hann);
    }

    inputFifo_.assign(kWindow, 0.0f);
    re_.assign(kWindow, 0.0f);
    im_.assign(kWindow, 0.0f);

    smoothedPower_.assign(kBins, 0.0f);
    noisePower_.assign(kBins, 0.0f);
    minTracker_.assign(kBins, 1.0f);
    minTemp_.assign(kBins, 1.0f);
    previousClean_.assign(kBins, 0.0f);

    overlap_.assign(kWindow, 0.0f);
    pending_.assign(kWindow * 4, 0.0f);
    dryDelay_.assign(kWindow - kHop, 0.0f);

    prepared_ = true;
    reset();
}

void SpectralNoiseSuppressor::reset() {
    if (!prepared_) return;

    std::fill(inputFifo_.begin(), inputFifo_.end(), 0.0f);
    std::fill(overlap_.begin(), overlap_.end(), 0.0f);
    std::fill(pending_.begin(), pending_.end(), 0.0f);
    std::fill(dryDelay_.begin(), dryDelay_.end(), 0.0f);
    std::fill(smoothedPower_.begin(), smoothedPower_.end(), 0.0f);
    std::fill(noisePower_.begin(), noisePower_.end(), 0.0f);
    std::fill(minTracker_.begin(), minTracker_.end(), 1.0f);
    std::fill(minTemp_.begin(), minTemp_.end(), 1.0f);
    std::fill(previousClean_.begin(), previousClean_.end(), 0.0f);

    // Prime the output queue with exactly the algorithmic latency, so there is
    // always a sample to hand back and the delay stays constant from the start.
    fifoFill_ = kWindow - kHop;
    pendingRead_ = 0;
    pendingWrite_ = kWindow - kHop;
    dryIndex_ = 0;
    minTrackerCountdown_ = kMinTrackerFrames;
}

void SpectralNoiseSuppressor::processHop() {
    for (size_t i = 0; i < kWindow; ++i) {
        re_[i] = inputFifo_[i] * window_[i];
        im_[i] = 0.0f;
    }
    fft_.forward(re_.data(), im_.data());

    const bool resetTracker = --minTrackerCountdown_ <= 0;
    if (resetTracker) minTrackerCountdown_ = kMinTrackerFrames;

    for (size_t k = 0; k < kBins; ++k) {
        const float power = re_[k] * re_[k] + im_[k] * im_[k];
        smoothedPower_[k] = kPowerSmoothing * smoothedPower_[k] + (1.0f - kPowerSmoothing) * power;

        // Minimum statistics: the floor of the smoothed power over a window longer
        // than any syllable is, by definition, the noise. Two alternating trackers
        // give a sliding minimum without storing the whole history.
        minTracker_[k] = std::min(minTracker_[k], smoothedPower_[k]);
        minTemp_[k] = std::min(minTemp_[k], smoothedPower_[k]);
        if (resetTracker) {
            noisePower_[k] = minTracker_[k] * kNoiseBias;
            minTracker_[k] = minTemp_[k];
            minTemp_[k] = smoothedPower_[k];
        }

        const float noise = std::max(noisePower_[k], kEpsilon);
        const float posterior = power / noise;

        // Decision-directed a priori SNR (Ephraim-Malah): blending in the previous
        // frame's clean estimate is what stops isolated bins from flickering, which
        // is heard as musical noise.
        const float priorFromPrevious = previousClean_[k] * previousClean_[k] / noise;
        const float prior = kAlphaDd * priorFromPrevious +
                            (1.0f - kAlphaDd) * std::max(posterior - 1.0f, 0.0f);

        float gain = prior / (1.0f + prior);
        gain = std::max(gain, kGainFloor);

        const float magnitude = std::sqrt(power);
        previousClean_[k] = gain * magnitude;

        re_[k] *= gain;
        im_[k] *= gain;
        if (k > 0 && k < kWindow / 2) {
            const size_t mirror = kWindow - k;
            re_[mirror] *= gain;
            im_[mirror] *= gain;
        }
    }

    fft_.inverse(re_.data(), im_.data());

    for (size_t i = 0; i < kWindow; ++i) {
        overlap_[i] += re_[i] * window_[i];
    }

    for (size_t i = 0; i < kHop; ++i) {
        pending_[pendingWrite_ % pending_.size()] = overlap_[i];
        ++pendingWrite_;
    }

    std::copy(overlap_.begin() + static_cast<long>(kHop), overlap_.end(), overlap_.begin());
    std::fill(overlap_.end() - static_cast<long>(kHop), overlap_.end(), 0.0f);

    std::copy(inputFifo_.begin() + static_cast<long>(kHop), inputFifo_.end(), inputFifo_.begin());
    fifoFill_ = kWindow - kHop;
}

void SpectralNoiseSuppressor::process(float* buffer, int numFrames, float mix) {
    if (!prepared_) return;
    const float wet = std::min(1.0f, std::max(0.0f, mix));
    const float dryGain = 1.0f - wet;

    for (int n = 0; n < numFrames; ++n) {
        const float input = buffer[n];

        // Dry path delayed by the same amount as the wet path, so the mix control
        // blends aligned signals instead of comb-filtering them.
        const float dry = dryDelay_[dryIndex_];
        dryDelay_[dryIndex_] = input;
        dryIndex_ = (dryIndex_ + 1) % dryDelay_.size();

        inputFifo_[fifoFill_++] = input;
        if (fifoFill_ >= kWindow) {
            processHop();
        }

        float clean = 0.0f;
        if (pendingRead_ < pendingWrite_) {
            clean = pending_[pendingRead_ % pending_.size()];
            ++pendingRead_;
        }

        buffer[n] = clean * wet + dry * dryGain;
    }
}

} // namespace mobimic::dsp
