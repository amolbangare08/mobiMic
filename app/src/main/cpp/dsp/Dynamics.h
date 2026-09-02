#pragma once

#include "Svf.h"

#include <algorithm>
#include <vector>

namespace mobimic::dsp {

/**
 * Downward expander with hysteresis and hold.
 *
 * Placed before the compressor: if it ran after, the compressor's makeup gain
 * would have already lifted the room noise the gate is trying to remove.
 */
class Gate {
public:
    void prepare(float sampleRate) {
        sampleRate_ = sampleRate;
        envelope_ = 0.0f;
        gainDb_ = 0.0f;
        holdCounter_ = 0;
    }

    void configure(float thresholdDb, float ratio, float attackMs, float holdMs,
                   float releaseMs, float hysteresisDb) {
        thresholdDb_ = thresholdDb;
        ratio_ = std::max(1.0f, ratio);
        attackCoeff_ = envelopeCoeff(sampleRate_, attackMs);
        releaseCoeff_ = envelopeCoeff(sampleRate_, releaseMs);
        holdSamples_ = static_cast<int>(holdMs * 0.001f * sampleRate_);
        hysteresisDb_ = hysteresisDb;
        detectorCoeff_ = envelopeCoeff(sampleRate_, 5.0f);
    }

    inline float process(float x) {
        const float rectified = std::fabs(x);
        envelope_ = rectified > envelope_
                ? rectified
                : rectified + detectorCoeff_ * (envelope_ - rectified);
        const float levelDb = linearToDb(envelope_);

        // Two thresholds: opening needs more level than staying open. Without the
        // hysteresis the gate chatters on every syllable tail.
        const float openDb = thresholdDb_;
        const float closeDb = thresholdDb_ - hysteresisDb_;
        const float effective = open_ ? closeDb : openDb;

        if (levelDb > effective) {
            open_ = true;
            holdCounter_ = holdSamples_;
        } else if (holdCounter_ > 0) {
            --holdCounter_;
        } else {
            open_ = false;
        }

        const float targetDb = open_
                ? 0.0f
                : (levelDb - closeDb) * (1.0f - 1.0f / ratio_); // negative below threshold

        const float coeff = targetDb < gainDb_ ? attackCoeff_ : releaseCoeff_;
        gainDb_ = targetDb + coeff * (gainDb_ - targetDb);

        lastGainDb_ = gainDb_;
        return x * dbToLinear(gainDb_);
    }

    float gainReductionDb() const { return lastGainDb_; }

private:
    float sampleRate_ = 48000.0f;
    float thresholdDb_ = -45.0f;
    float ratio_ = 4.0f;
    float hysteresisDb_ = 6.0f;
    float attackCoeff_ = 0.0f;
    float releaseCoeff_ = 0.0f;
    float detectorCoeff_ = 0.0f;
    float envelope_ = 0.0f;
    float gainDb_ = 0.0f;
    float lastGainDb_ = 0.0f;
    int holdSamples_ = 0;
    int holdCounter_ = 0;
    bool open_ = false;
};

/**
 * Feed-forward compressor with a soft knee, computed in the log domain.
 *
 * Log-domain because ratio and knee are defined in dB; doing the maths there keeps
 * the curve exactly as specified instead of approximating it in linear gain.
 */
class Compressor {
public:
    void prepare(float sampleRate) {
        sampleRate_ = sampleRate;
        envelope_ = 0.0f;
        gainReductionDb_ = 0.0f;
    }

    void configure(float thresholdDb, float ratio, float kneeDb, float attackMs,
                   float releaseMs, float makeupDb, bool autoMakeup) {
        thresholdDb_ = thresholdDb;
        ratio_ = std::max(1.0f, ratio);
        kneeDb_ = std::max(0.0f, kneeDb);
        attackCoeff_ = envelopeCoeff(sampleRate_, attackMs);
        releaseCoeff_ = envelopeCoeff(sampleRate_, releaseMs);

        // Auto makeup restores roughly the level lost at the threshold, which keeps
        // A/B comparisons honest instead of louder-equals-better.
        makeupDb_ = autoMakeup
                ? makeupDb + (-thresholdDb_) * (1.0f - 1.0f / ratio_) * 0.5f
                : makeupDb;
    }

    inline float process(float x) {
        const float rectified = std::fabs(x);
        envelope_ = rectified > envelope_ ? rectified : rectified + 0.9995f * (envelope_ - rectified);
        const float levelDb = linearToDb(envelope_);

        const float over = levelDb - thresholdDb_;
        float targetReductionDb;
        if (over <= -kneeDb_ * 0.5f) {
            targetReductionDb = 0.0f;
        } else if (over < kneeDb_ * 0.5f && kneeDb_ > 0.0f) {
            const float t = over + kneeDb_ * 0.5f;
            targetReductionDb = (1.0f / ratio_ - 1.0f) * t * t / (2.0f * kneeDb_);
        } else {
            targetReductionDb = (1.0f / ratio_ - 1.0f) * over;
        }

        // Smooth the gain reduction, not the envelope: attack and release then mean
        // what the labels say they mean.
        const float coeff = targetReductionDb < gainReductionDb_ ? attackCoeff_ : releaseCoeff_;
        gainReductionDb_ = targetReductionDb + coeff * (gainReductionDb_ - targetReductionDb);

        return x * dbToLinear(gainReductionDb_ + makeupDb_);
    }

    float gainReductionDb() const { return gainReductionDb_; }

private:
    float sampleRate_ = 48000.0f;
    float thresholdDb_ = -22.0f;
    float ratio_ = 3.0f;
    float kneeDb_ = 6.0f;
    float makeupDb_ = 0.0f;
    float attackCoeff_ = 0.0f;
    float releaseCoeff_ = 0.0f;
    float envelope_ = 0.0f;
    float gainReductionDb_ = 0.0f;
};

/**
 * Split-band de-esser.
 *
 * The band split is complementary (high = input - low), so bypassing the
 * compression leaves the signal magnitude-flat. Wideband de-essing would duck the
 * whole voice on every "s"; this only touches the sibilant band.
 */
class DeEsser {
public:
    void prepare(float sampleRate) {
        sampleRate_ = sampleRate;
        low_.setSampleRate(sampleRate);
        low_.reset();
        compressor_.prepare(sampleRate);
    }

    void configure(float splitHz, float thresholdDb, float ratio) {
        low_.set(Svf::Type::LowPass, splitHz, 0.7071f);
        compressor_.configure(thresholdDb, ratio, 4.0f, 1.0f, 60.0f, 0.0f, false);
    }

    inline float process(float x) {
        const float low = low_.process(x);
        const float high = x - low;
        return low + compressor_.process(high);
    }

    float gainReductionDb() const { return compressor_.gainReductionDb(); }

private:
    float sampleRate_ = 48000.0f;
    Svf low_;
    Compressor compressor_;
};

/**
 * Brickwall limiter with lookahead.
 *
 * The delay line buys time to bring the gain down before the peak arrives, so the
 * attack can be effectively instantaneous without a click. The final clamp is a
 * safety net for the small overshoot the smoother allows, not the main mechanism.
 */
class Limiter {
public:
    void prepare(float sampleRate, float maxLookaheadMs) {
        sampleRate_ = sampleRate;
        const int maxDelay = static_cast<int>(maxLookaheadMs * 0.001f * sampleRate) + 4;
        delay_.assign(static_cast<size_t>(maxDelay), 0.0f);
        writeIndex_ = 0;
        gain_ = 1.0f;
    }

    void configure(float ceilingDb, float releaseMs, float lookaheadMs) {
        ceiling_ = dbToLinear(ceilingDb);
        releaseCoeff_ = envelopeCoeff(sampleRate_, releaseMs);
        lookahead_ = std::min(static_cast<int>(lookaheadMs * 0.001f * sampleRate_),
                              static_cast<int>(delay_.size()) - 1);
        lookahead_ = std::max(lookahead_, 1);
        attackCoeff_ = envelopeCoeff(sampleRate_, lookaheadMs / 3.0f);
    }

    inline float process(float x) {
        const size_t size = delay_.size();
        delay_[writeIndex_] = x;
        const size_t readIndex = (writeIndex_ + size - static_cast<size_t>(lookahead_)) % size;
        const float delayed = delay_[readIndex];
        writeIndex_ = (writeIndex_ + 1) % size;

        // The incoming (not yet audible) sample decides the gain.
        const float magnitude = std::fabs(x);
        const float targetGain = magnitude > ceiling_ ? ceiling_ / magnitude : 1.0f;
        const float coeff = targetGain < gain_ ? attackCoeff_ : releaseCoeff_;
        gain_ = targetGain + coeff * (gain_ - targetGain);

        const float out = delayed * gain_;
        return std::max(-ceiling_, std::min(ceiling_, out));
    }

    float gainReductionDb() const { return linearToDb(gain_); }
    int latencySamples() const { return lookahead_; }

private:
    float sampleRate_ = 48000.0f;
    std::vector<float> delay_;
    size_t writeIndex_ = 0;
    int lookahead_ = 96;
    float ceiling_ = 0.891f;
    float gain_ = 1.0f;
    float attackCoeff_ = 0.0f;
    float releaseCoeff_ = 0.0f;
};

/**
 * Soft saturation with 2x oversampling.
 *
 * tanh generates harmonics above Nyquist; without oversampling those fold back as
 * inharmonic aliasing, which sounds like grit rather than warmth. Two times is
 * enough for the gentle drive range exposed here.
 */
class Saturator {
public:
    void prepare(float sampleRate) {
        upFilter_.setSampleRate(sampleRate * 2.0f);
        downFilter_.setSampleRate(sampleRate * 2.0f);
        upFilter_.set(Svf::Type::LowPass, sampleRate * 0.45f, 0.7071f);
        downFilter_.set(Svf::Type::LowPass, sampleRate * 0.45f, 0.7071f);
        upFilter_.reset();
        downFilter_.reset();
    }

    void configure(float driveDb, float mix) {
        drive_ = dbToLinear(driveDb);
        mix_ = std::min(1.0f, std::max(0.0f, mix));
        compensation_ = 1.0f / std::tanh(std::max(drive_, 1.0e-3f));
    }

    inline float process(float x) {
        // Zero-stuffed 2x upsample, shape, then filter and decimate.
        const float a = upFilter_.process(x * 2.0f);
        const float b = upFilter_.process(0.0f);
        const float sa = std::tanh(a * drive_) * compensation_;
        const float sb = std::tanh(b * drive_) * compensation_;
        downFilter_.process(sa);
        const float shaped = downFilter_.process(sb);
        return x * (1.0f - mix_) + shaped * mix_;
    }

private:
    Svf upFilter_;
    Svf downFilter_;
    float drive_ = 1.0f;
    float mix_ = 0.5f;
    float compensation_ = 1.0f;
};

} // namespace mobimic::dsp
