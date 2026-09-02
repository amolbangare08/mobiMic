#pragma once

namespace mobimic {

/**
 * Interface every noise suppressor implements.
 *
 * The point of the indirection: the model is expected to change. Phase 4 ships a
 * spectral suppressor that needs no third-party code, and a learned model
 * (RNNoise, GTCRN, DeepFilterNet) can be dropped in later as another
 * implementation rather than a rewrite of the chain.
 *
 * process() is called from the audio thread, so implementations must preallocate
 * everything in prepare() and must not lock, allocate or do I/O.
 */
class NoiseSuppressor {
public:
    virtual ~NoiseSuppressor() = default;

    virtual void prepare(float sampleRate, int maxBlockFrames) = 0;
    virtual void reset() = 0;

    /**
     * Processes in place.
     * @param mix 0 = dry, 1 = fully suppressed. Anything between blends, which is
     *            how you trade artefacts against noise floor.
     */
    virtual void process(float* buffer, int numFrames, float mix) = 0;

    /** Algorithmic delay this suppressor adds, in frames. */
    virtual int latencyFrames() const = 0;

    virtual const char* name() const = 0;
};

} // namespace mobimic
