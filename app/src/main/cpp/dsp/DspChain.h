#pragma once

#include <atomic>

#include "Dynamics.h"
#include "Params.h"
#include "Svf.h"

namespace mobimic {
class NoiseSuppressor; // Phase 4, forward-declared to keep the chain independent of it.
}

namespace mobimic::dsp {

/**
 * The voice chain, processed in place on the audio thread.
 *
 * Order: input gain -> HPF -> gate -> noise suppression -> EQ -> de-esser
 *        -> compressor -> saturation -> output gain -> limiter
 *
 * Every block is preallocated in prepare(). process() allocates nothing, takes no
 * locks and makes no system calls.
 */
class DspChain {
public:
    void prepare(float sampleRate, int maxBlockSize);
    void reset();

    /** Attaches the Phase 4 suppressor. Null disables that stage. */
    void setNoiseSuppressor(NoiseSuppressor* suppressor) { suppressor_ = suppressor; }

    /** Audio thread. Processes in place. */
    void process(float* buffer, int numFrames, const DspParams& params);

    float gateReductionDb() const { return gateReductionDb_.load(std::memory_order_relaxed); }
    float compReductionDb() const { return compReductionDb_.load(std::memory_order_relaxed); }
    float deEsserReductionDb() const { return deEsserReductionDb_.load(std::memory_order_relaxed); }
    float limiterReductionDb() const { return limiterReductionDb_.load(std::memory_order_relaxed); }
    float outputPeak() const { return outputPeak_.load(std::memory_order_relaxed); }

    /** Algorithmic delay introduced by the chain, in frames. */
    int latencyFrames() const { return latencyFrames_; }

private:
    void applyParams(const DspParams& params);

    float sampleRate_ = 48000.0f;
    uint32_t appliedVersion_ = 0xFFFFFFFFu;

    // Two cascaded Butterworth sections give the 24 dB/oct high-pass.
    Svf hpf_[2];
    Svf eq_[kEqBands];
    bool eqActive_[kEqBands] = {};

    Gate gate_;
    DeEsser deEsser_;
    Compressor compressor_;
    Saturator saturator_;
    Limiter limiter_;

    Smoother inputGain_;
    Smoother outputGain_;

    NoiseSuppressor* suppressor_ = nullptr;

    int latencyFrames_ = 0;

    std::atomic<float> gateReductionDb_{0.0f};
    std::atomic<float> compReductionDb_{0.0f};
    std::atomic<float> deEsserReductionDb_{0.0f};
    std::atomic<float> limiterReductionDb_{0.0f};
    std::atomic<float> outputPeak_{0.0f};
};

} // namespace mobimic::dsp
