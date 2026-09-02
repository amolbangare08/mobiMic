#pragma once

#include <cmath>

namespace mobimic::dsp {

/**
 * Topology-preserving transform state variable filter (Simper / Zavalishin form).
 *
 * Chosen over a direct-form biquad because the state stays meaningful when the
 * coefficients change: a user dragging an EQ slider retunes this filter without
 * the zipper noise and transient blowups a direct-form structure produces.
 *
 * One instance is one 2nd-order section. Cascade for steeper slopes.
 */
class Svf {
public:
    enum class Type { LowPass, HighPass, BandPass, Notch, Bell, LowShelf, HighShelf };

    void reset() {
        ic1eq_ = 0.0f;
        ic2eq_ = 0.0f;
    }

    void setSampleRate(float sampleRate) {
        sampleRate_ = sampleRate;
    }

    void set(Type type, float frequencyHz, float q, float gainDb = 0.0f) {
        const float nyquist = sampleRate_ * 0.5f;
        const float f = std::fmin(std::fmax(frequencyHz, 10.0f), nyquist * 0.98f);
        const float A = std::pow(10.0f, gainDb / 40.0f);

        float g = std::tan(3.14159265358979f * f / sampleRate_);
        float k = 1.0f / std::fmax(q, 0.05f);

        switch (type) {
            case Type::LowPass:
                m0_ = 0.0f; m1_ = 0.0f; m2_ = 1.0f;
                break;
            case Type::HighPass:
                m0_ = 1.0f; m1_ = -k; m2_ = -1.0f;
                break;
            case Type::BandPass:
                m0_ = 0.0f; m1_ = 1.0f; m2_ = 0.0f;
                break;
            case Type::Notch:
                m0_ = 1.0f; m1_ = -k; m2_ = 0.0f;
                break;
            case Type::Bell:
                k = 1.0f / (std::fmax(q, 0.05f) * A);
                m0_ = 1.0f; m1_ = k * (A * A - 1.0f); m2_ = 0.0f;
                break;
            case Type::LowShelf:
                g = g / std::sqrt(A);
                m0_ = 1.0f; m1_ = k * (A - 1.0f); m2_ = A * A - 1.0f;
                break;
            case Type::HighShelf:
                g = g * std::sqrt(A);
                m0_ = A * A; m1_ = k * (1.0f - A) * A; m2_ = 1.0f - A * A;
                break;
        }

        a1_ = 1.0f / (1.0f + g * (g + k));
        a2_ = g * a1_;
        a3_ = g * a2_;
    }

    inline float process(float v0) {
        const float v3 = v0 - ic2eq_;
        const float v1 = a1_ * ic1eq_ + a2_ * v3;
        const float v2 = ic2eq_ + a2_ * ic1eq_ + a3_ * v3;
        ic1eq_ = 2.0f * v1 - ic1eq_;
        ic2eq_ = 2.0f * v2 - ic2eq_;
        return m0_ * v0 + m1_ * v1 + m2_ * v2;
    }

private:
    float sampleRate_ = 48000.0f;
    float a1_ = 0.0f, a2_ = 0.0f, a3_ = 0.0f;
    float m0_ = 1.0f, m1_ = 0.0f, m2_ = 0.0f;
    float ic1eq_ = 0.0f, ic2eq_ = 0.0f;
};

/** One-pole parameter smoother. Un-smoothed gain changes are the main source of clicks. */
class Smoother {
public:
    void configure(float sampleRate, float timeMs) {
        coeff_ = std::exp(-1.0f / (sampleRate * timeMs * 0.001f));
    }

    void snapTo(float value) { current_ = value; }

    inline float next(float target) {
        current_ = target + coeff_ * (current_ - target);
        return current_;
    }

    float value() const { return current_; }

private:
    float coeff_ = 0.0f;
    float current_ = 0.0f;
};

inline float dbToLinear(float db) { return std::pow(10.0f, db * 0.05f); }

inline float linearToDb(float linear) {
    return 20.0f * std::log10(std::fmax(linear, 1.0e-9f));
}

/** Time constant for a one-pole envelope follower. */
inline float envelopeCoeff(float sampleRate, float timeMs) {
    if (timeMs <= 0.0f) return 0.0f;
    return std::exp(-1.0f / (sampleRate * timeMs * 0.001f));
}

} // namespace mobimic::dsp
