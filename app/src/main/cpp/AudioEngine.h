#pragma once

#include <oboe/Oboe.h>

#include <atomic>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "RingBuffer.h"
#include "WavWriter.h"
#include "dsp/DspChain.h"
#include "dsp/Params.h"
#include "dsp/SpectralNoiseSuppressor.h"
#include "net/UdpSender.h"

namespace mobimic {

/** Which input preset the stream actually got, after fallbacks. */
enum class PresetUsed : int32_t {
    None = 0,
    Unprocessed = 1,
    VoiceRecognition = 2,
    Generic = 3,
};

/**
 * Owns the Oboe input stream and the drain thread.
 *
 * Thread layout:
 *   - Audio callback thread: metering + ring write only. No allocation, no locks,
 *     no JNI, no logging, no syscalls. See onAudioReady().
 *   - Drain thread: consumes the ring, writes WAV when armed, tunes the buffer
 *     size against the XRun counter, and rebuilds the stream after a disconnect.
 *   - Any thread: open/start/stop/close, guarded by a mutex.
 */
class AudioEngine : public oboe::AudioStreamDataCallback,
                    public oboe::AudioStreamErrorCallback {
public:
    static AudioEngine& instance();

    /**
     * Opens the input stream (does not start it).
     *
     * @param allocateSession requests an audio session id so Kotlin can attach to
     *        AGC/NS/AEC and force them off. This is not free: AAudio refuses the
     *        MMAP low-latency path for any stream with a session id, because
     *        effects have to run in the mixer. Devices that honour Unprocessed do
     *        not need it, and get a far smaller burst without it.
     * @return the audio session id on success, or a negative oboe::Result.
     */
    int32_t open(bool allocateSession);

    bool start();
    void stop();
    void close();

    /** Where the WAV recorder taps the signal. */
    enum class RecordSource : int32_t { Raw = 0, Processed = 1 };

    /**
     * Arms WAV capture. Safe to call while running.
     *
     * Raw taps before the DSP chain, which is what the capture-quality
     * verification needs; Processed taps what actually goes on the wire.
     */
    bool startRecording(const std::string& path, RecordSource source);
    void stopRecording();

    /** Phase 2 transport. Safe to call while running. */
    bool setTarget(const std::string& host, uint16_t port);
    void setLocalAddress(const std::string& address) { sender_.setLocalAddress(address); }
    void setOverUsb(bool overUsb) { sender_.setOverUsb(overUsb); }
    bool startStreaming(int32_t framesPerPacket, int32_t wireFormat);
    void stopStreaming();
    bool isStreaming() const { return sender_.isStreaming(); }
    int64_t packetsSent() const { return sender_.packetsSent(); }
    int64_t bytesSent() const { return sender_.bytesSent(); }
    int64_t sendErrors() const { return sender_.sendErrors(); }
    std::string targetDescription() { return sender_.targetDescription(); }

    /** Phase 3/4 DSP. The publisher is the only way parameters cross to the audio thread. */
    dsp::ParamPublisher& params() { return params_; }
    const dsp::DspChain& chain() const { return chain_; }
    int dspLatencyFrames() const { return chain_.latencyFrames(); }

    bool isRunning() const { return running_.load(std::memory_order_acquire); }
    bool isRecording() const { return recording_.load(std::memory_order_acquire); }

    int32_t sessionId() const { return sessionId_; }
    bool mmapUsed() const { return mmapUsed_; }
    bool lowLatencyGranted() const { return lowLatencyGranted_; }
    bool exclusiveGranted() const { return exclusiveGranted_; }
    PresetUsed presetUsed() const { return presetUsed_; }
    int32_t sampleRate() const { return sampleRate_; }
    int32_t framesPerBurst() const { return framesPerBurst_; }
    int32_t bufferSizeFrames() const { return bufferSizeFrames_.load(std::memory_order_relaxed); }
    int32_t bufferCapacityFrames() const { return bufferCapacityFrames_; }
    int32_t xRunCount() const { return xRunCount_.load(std::memory_order_relaxed); }
    int32_t callbackFrames() const { return callbackFrames_.load(std::memory_order_relaxed); }
    int64_t framesCaptured() const { return framesCaptured_.load(std::memory_order_relaxed); }
    int64_t framesDropped() const { return framesDropped_.load(std::memory_order_relaxed); }
    float peakLevel() const { return peak_.load(std::memory_order_relaxed); }
    float rmsLevel() const { return rms_.load(std::memory_order_relaxed); }
    /** Worst observed callback duration as a fraction of its deadline. */
    float callbackLoad() const { return callbackLoad_.load(std::memory_order_relaxed); }
    /** Hardware latency reported by the stream's own timestamps, in ms. 0 if unavailable. */
    float measuredLatencyMs() const { return measuredLatencyMs_.load(std::memory_order_relaxed); }
    int64_t recordedFrames() const { return recordedFrames_.load(std::memory_order_relaxed); }
    std::string recordingPath();

    void resetStats();

    /**
     * Opens a matrix of stream configurations, records what each one actually
     * yields, closes them, and logs the table.
     *
     * The device decides which requests earn the MMAP low-latency path, and it does
     * not explain itself. Measuring is the only way to find out which constraint is
     * the expensive one.
     */
    void probePaths();

    // oboe::AudioStreamDataCallback
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* stream,
                                          void* audioData,
                                          int32_t numFrames) override;

    // oboe::AudioStreamErrorCallback
    void onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) override;

private:
    AudioEngine() = default;
    ~AudioEngine() override;
    AudioEngine(const AudioEngine&) = delete;
    AudioEngine& operator=(const AudioEngine&) = delete;

    oboe::Result openWithPreset(oboe::InputPreset preset, bool allocateSession,
                                oboe::AudioFormat format, int32_t channels);
    /** Opens the lowest-latency configuration the device will actually grant. */
    oboe::Result openBest(oboe::InputPreset preset, bool allocateSession);
    void drainLoop();
    void tuneBufferSize();

    mutable std::mutex lock_;
    std::shared_ptr<oboe::AudioStream> stream_;

    std::unique_ptr<RingBuffer<float>> ring_;      // post-DSP, feeds the network
    std::unique_ptr<RingBuffer<float>> rawRing_;   // pre-DSP, feeds the raw WAV tap
    std::thread drainThread_;
    std::atomic<bool> drainRunning_{false};
    std::atomic<bool> running_{false};
    std::atomic<bool> needsRestart_{false};

    // WAV capture. Touched by the drain thread only, except the atomics.
    std::mutex recordLock_;
    WavWriter writer_;
    std::string pendingRecordPath_;
    std::atomic<bool> recordRequested_{false};
    std::atomic<RecordSource> recordSource_{RecordSource::Raw};
    std::atomic<bool> recording_{false};
    std::atomic<int64_t> recordedFrames_{0};

    UdpSender sender_;

    dsp::ParamPublisher params_;
    dsp::DspChain chain_;
    dsp::SpectralNoiseSuppressor noiseSuppressor_;

    // Stream properties, written once at open.
    int32_t sessionId_ = -1;
    int32_t sampleRate_ = 0;
    int32_t framesPerBurst_ = 0;
    int32_t bufferCapacityFrames_ = 0;
    PresetUsed presetUsed_ = PresetUsed::None;
    bool mmapUsed_ = false;
    // What the device gave us, which is not always what we asked for.
    oboe::AudioFormat deviceFormat_ = oboe::AudioFormat::Float;
    int32_t deviceChannels_ = 1;
    int32_t micChannel_ = 0;
    std::vector<float> scratch_;   // device frames converted to mono float
    bool lowLatencyGranted_ = false;
    bool exclusiveGranted_ = false;
    bool lastAllocateSession_ = false;

    // Drain-thread only.
    int32_t lastTunedXRuns_ = 0;
    oboe::InputPreset lastPreset_ = oboe::InputPreset::Unprocessed;

    // Live stats.
    std::atomic<int32_t> bufferSizeFrames_{0};
    std::atomic<int32_t> xRunCount_{0};
    std::atomic<int32_t> callbackFrames_{0};
    std::atomic<int64_t> framesCaptured_{0};
    std::atomic<int64_t> framesDropped_{0};
    std::atomic<float> peak_{0.0f};
    std::atomic<float> rms_{0.0f};
    std::atomic<float> callbackLoad_{0.0f};
    std::atomic<float> measuredLatencyMs_{0.0f};
};

} // namespace mobimic
