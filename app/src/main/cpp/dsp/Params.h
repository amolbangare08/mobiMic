#pragma once

#include <atomic>
#include <cstdint>

namespace mobimic::dsp {

constexpr int kEqBands = 5;

/** Band roles are fixed; only frequency, gain and Q are user-editable. */
enum class EqBandType : int32_t {
    LowShelf = 0,
    Bell = 1,
    HighShelf = 2,
};

struct EqBand {
    bool enabled = true;
    EqBandType type = EqBandType::Bell;
    float frequencyHz = 1000.0f;
    float gainDb = 0.0f;
    float q = 0.7f;
};

/**
 * Plain-old-data parameter block.
 *
 * Copied wholesale between threads, never pointed into. No std::string, no
 * containers, nothing that allocates - the audio thread reads this directly.
 */
struct DspParams {
    uint32_t version = 0;

    bool enabled = false;          // master bypass for the whole chain
    float inputGainDb = 0.0f;
    float outputGainDb = 0.0f;

    // High-pass: two cascaded Butterworth sections, 24 dB/oct.
    bool hpfEnabled = true;
    float hpfHz = 80.0f;

    // Downward expander / gate.
    bool gateEnabled = true;
    float gateThresholdDb = -45.0f;
    float gateRatio = 4.0f;
    float gateAttackMs = 1.0f;
    float gateHoldMs = 50.0f;
    float gateReleaseMs = 200.0f;
    float gateHysteresisDb = 6.0f;

    // Noise suppression (Phase 4). Kept here so the UI and the chain agree on one
    // parameter block rather than two.
    bool nsEnabled = false;
    float nsMix = 1.0f;

    bool eqEnabled = true;
    EqBand eq[kEqBands] = {
        {true, EqBandType::LowShelf, 120.0f, 0.0f, 0.7f},
        {true, EqBandType::Bell, 300.0f, 0.0f, 1.0f},
        {true, EqBandType::Bell, 1200.0f, 0.0f, 1.0f},
        {true, EqBandType::Bell, 4000.0f, 0.0f, 1.0f},
        {true, EqBandType::HighShelf, 9000.0f, 0.0f, 0.7f},
    };

    bool deEsserEnabled = true;
    float deEsserSplitHz = 5000.0f;
    float deEsserThresholdDb = -28.0f;
    float deEsserRatio = 4.0f;

    bool compressorEnabled = true;
    float compThresholdDb = -22.0f;
    float compRatio = 3.0f;
    float compKneeDb = 6.0f;
    float compAttackMs = 10.0f;
    float compReleaseMs = 120.0f;
    float compMakeupDb = 0.0f;
    bool compAutoMakeup = true;

    bool saturationEnabled = false;
    float saturationDriveDb = 3.0f;
    float saturationMix = 0.5f;

    bool limiterEnabled = true;
    float limiterCeilingDb = -1.0f;
    float limiterReleaseMs = 80.0f;
    float limiterLookaheadMs = 2.0f;
};

/**
 * Triple-buffered publisher.
 *
 * One writer (the UI thread through JNI), one reader (the audio thread). The
 * writer fills a slot nobody is reading and publishes its index with a release
 * store; the reader acquire-loads the index. No locks, no allocation, no torn
 * reads, and the audio thread never waits on the UI.
 */
class ParamPublisher {
public:
    ParamPublisher() {
        slots_[0] = DspParams{};
        slots_[1] = slots_[0];
        slots_[2] = slots_[0];
    }

    /** Writer side: the slot currently safe to modify. */
    DspParams& editable() { return slots_[writeIndex_]; }

    /** Writer side: makes the edited slot live and moves on to the next spare. */
    void publish() {
        slots_[writeIndex_].version = ++version_;
        const int published = writeIndex_;
        live_.store(published, std::memory_order_release);

        // Move to a slot that is neither live nor the one just published.
        const int next = (published + 1) % 3;
        writeIndex_ = next;
        slots_[writeIndex_] = slots_[published];
    }

    /** Reader side: the currently live parameters. */
    const DspParams& current() const {
        return slots_[live_.load(std::memory_order_acquire)];
    }

private:
    DspParams slots_[3];
    std::atomic<int> live_{0};
    int writeIndex_ = 1;
    uint32_t version_ = 0;
};

} // namespace mobimic::dsp
