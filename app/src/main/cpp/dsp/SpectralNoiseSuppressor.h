#pragma once

#include <vector>

#include "../NoiseSuppressor.h"
#include "Fft.h"

namespace mobimic::dsp {

/**
 * STFT noise suppressor: minimum-statistics noise floor plus a decision-directed
 * Wiener gain.
 *
 * Why this and not a learned model first: it needs no third-party code, no model
 * file and no inference runtime, so the whole Phase 4 plumbing - block
 * accumulation, latency accounting, mix control, CPU budget - gets proven with
 * something that actually works. Swapping in RNNoise or DeepFilterNet afterwards
 * is then a matter of writing one more NoiseSuppressor.
 *
 * Decision-directed a priori SNR estimation (Ephraim-Malah) is what keeps this
 * from sounding like classic spectral subtraction: the smoothing across frames
 * suppresses the isolated random bins that are heard as musical noise.
 *
 * 512-point window with a 256-sample hop at 48 kHz: 5.33 ms of algorithmic delay
 * and roughly 190 transforms per second, which is cheap enough to run inline in
 * the audio callback.
 */
class SpectralNoiseSuppressor : public NoiseSuppressor {
public:
    void prepare(float sampleRate, int maxBlockFrames) override;
    void reset() override;
    void process(float* buffer, int numFrames, float mix) override;
    int latencyFrames() const override { return static_cast<int>(kWindow - kHop); }
    const char* name() const override { return "spectral"; }

private:
    static constexpr size_t kWindow = 512;
    static constexpr size_t kHop = 256;
    static constexpr size_t kBins = kWindow / 2 + 1;

    void processHop();

    Fft fft_;
    std::vector<float> window_;

    std::vector<float> inputFifo_;   // kWindow samples of history
    size_t fifoFill_ = 0;

    std::vector<float> re_;
    std::vector<float> im_;

    std::vector<float> smoothedPower_;
    std::vector<float> noisePower_;
    std::vector<float> minTracker_;
    std::vector<float> minTemp_;
    std::vector<float> previousClean_;

    std::vector<float> overlap_;     // overlap-add accumulator, kWindow long
    std::vector<float> pending_;     // processed samples waiting to be handed back
    size_t pendingRead_ = 0;
    size_t pendingWrite_ = 0;

    std::vector<float> dryDelay_;    // dry path, delayed to match the NS latency
    size_t dryIndex_ = 0;

    int minTrackerCountdown_ = 0;
    bool prepared_ = false;
};

} // namespace mobimic::dsp
