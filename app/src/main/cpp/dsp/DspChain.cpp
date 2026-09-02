#include "DspChain.h"

#include "../NoiseSuppressor.h"

namespace mobimic::dsp {

namespace {

/**
 * Butterworth Q values for a 4th-order (24 dB/oct) cascade.
 * Two 2nd-order sections at these Qs sum to a maximally flat passband.
 */
constexpr float kButterworthQ[2] = {0.5412f, 1.3066f};

Svf::Type toSvfType(EqBandType type) {
    switch (type) {
        case EqBandType::LowShelf: return Svf::Type::LowShelf;
        case EqBandType::HighShelf: return Svf::Type::HighShelf;
        case EqBandType::Bell:
        default: return Svf::Type::Bell;
    }
}

} // namespace

void DspChain::prepare(float sampleRate, int /*maxBlockSize*/) {
    sampleRate_ = sampleRate;

    for (auto& section : hpf_) {
        section.setSampleRate(sampleRate);
        section.reset();
    }
    for (auto& band : eq_) {
        band.setSampleRate(sampleRate);
        band.reset();
    }

    gate_.prepare(sampleRate);
    deEsser_.prepare(sampleRate);
    compressor_.prepare(sampleRate);
    saturator_.prepare(sampleRate);
    limiter_.prepare(sampleRate, 10.0f);

    // 20 ms is slow enough to be inaudible on a slider drag, fast enough that the
    // control still feels immediate.
    inputGain_.configure(sampleRate, 20.0f);
    outputGain_.configure(sampleRate, 20.0f);
    inputGain_.snapTo(1.0f);
    outputGain_.snapTo(1.0f);

    appliedVersion_ = 0xFFFFFFFFu;
}

void DspChain::reset() {
    for (auto& section : hpf_) section.reset();
    for (auto& band : eq_) band.reset();
    gate_.prepare(sampleRate_);
    deEsser_.prepare(sampleRate_);
    compressor_.prepare(sampleRate_);
    saturator_.prepare(sampleRate_);
    limiter_.prepare(sampleRate_, 10.0f);
}

/**
 * Recomputes coefficients. Called at most once per block, and only when the
 * published parameter version has actually changed.
 */
void DspChain::applyParams(const DspParams& params) {
    for (int i = 0; i < 2; ++i) {
        hpf_[i].set(Svf::Type::HighPass, params.hpfHz, kButterworthQ[i]);
    }

    for (int i = 0; i < kEqBands; ++i) {
        const EqBand& band = params.eq[i];
        eqActive_[i] = band.enabled && std::fabs(band.gainDb) > 0.01f;
        if (eqActive_[i]) {
            eq_[i].set(toSvfType(band.type), band.frequencyHz, band.q, band.gainDb);
        }
    }

    gate_.configure(params.gateThresholdDb, params.gateRatio, params.gateAttackMs,
                    params.gateHoldMs, params.gateReleaseMs, params.gateHysteresisDb);
    deEsser_.configure(params.deEsserSplitHz, params.deEsserThresholdDb, params.deEsserRatio);
    compressor_.configure(params.compThresholdDb, params.compRatio, params.compKneeDb,
                          params.compAttackMs, params.compReleaseMs, params.compMakeupDb,
                          params.compAutoMakeup);
    saturator_.configure(params.saturationDriveDb, params.saturationMix);
    limiter_.configure(params.limiterCeilingDb, params.limiterReleaseMs, params.limiterLookaheadMs);

    // Report the whole algorithmic delay, not just the limiter's. The suppressor's
    // STFT window is the larger of the two whenever it is switched on.
    latencyFrames_ = params.limiterEnabled ? limiter_.latencySamples() : 0;
    if (params.nsEnabled && suppressor_ != nullptr) {
        latencyFrames_ += suppressor_->latencyFrames();
    }
    appliedVersion_ = params.version;
}

void DspChain::process(float* buffer, int numFrames, const DspParams& params) {
    if (params.version != appliedVersion_) {
        applyParams(params);
    }

    if (!params.enabled) {
        // Master bypass must be a true bypass: no gain, no filtering, nothing.
        float peak = 0.0f;
        for (int i = 0; i < numFrames; ++i) peak = std::fmax(peak, std::fabs(buffer[i]));
        outputPeak_.store(peak, std::memory_order_relaxed);
        return;
    }

    const float inputTarget = dbToLinear(params.inputGainDb);
    const float outputTarget = dbToLinear(params.outputGainDb);

    // Phase 4 stage. Runs on whole blocks, so it sits outside the per-sample loop.
    if (params.nsEnabled && suppressor_ != nullptr) {
        suppressor_->process(buffer, numFrames, params.nsMix);
    }

    float peak = 0.0f;
    for (int i = 0; i < numFrames; ++i) {
        float x = buffer[i] * inputGain_.next(inputTarget);

        if (params.hpfEnabled) {
            x = hpf_[0].process(x);
            x = hpf_[1].process(x);
        }

        if (params.gateEnabled) {
            x = gate_.process(x);
        }

        if (params.eqEnabled) {
            for (int band = 0; band < kEqBands; ++band) {
                if (eqActive_[band]) x = eq_[band].process(x);
            }
        }

        if (params.deEsserEnabled) {
            x = deEsser_.process(x);
        }

        if (params.compressorEnabled) {
            x = compressor_.process(x);
        }

        if (params.saturationEnabled) {
            x = saturator_.process(x);
        }

        x *= outputGain_.next(outputTarget);

        if (params.limiterEnabled) {
            x = limiter_.process(x);
        }

        buffer[i] = x;
        peak = std::fmax(peak, std::fabs(x));
    }

    gateReductionDb_.store(gate_.gainReductionDb(), std::memory_order_relaxed);
    compReductionDb_.store(compressor_.gainReductionDb(), std::memory_order_relaxed);
    deEsserReductionDb_.store(deEsser_.gainReductionDb(), std::memory_order_relaxed);
    limiterReductionDb_.store(limiter_.gainReductionDb(), std::memory_order_relaxed);
    outputPeak_.store(peak, std::memory_order_relaxed);
}

} // namespace mobimic::dsp
