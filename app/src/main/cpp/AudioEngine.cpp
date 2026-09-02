#include "AudioEngine.h"

#include "Common.h"

#include <oboe/OboeExtensions.h>

#include <chrono>
#include <cmath>
#include <ctime>

namespace mobimic {

namespace {

constexpr int32_t kSampleRate = 48000;
constexpr int32_t kChannels = 1;
constexpr int32_t kRingSeconds = 2;
constexpr int32_t kMaxBufferBursts = 8;
constexpr size_t kDrainChunkFrames = 2048;

inline int64_t nowNanos() {
    // clock_gettime(CLOCK_MONOTONIC) resolves through the vDSO, so it does not
    // trap into the kernel and is safe to call from the audio callback.
    struct timespec ts {};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<int64_t>(ts.tv_sec) * 1000000000LL + ts.tv_nsec;
}

const char* presetName(oboe::InputPreset p) {
    switch (p) {
        case oboe::InputPreset::Unprocessed: return "Unprocessed";
        case oboe::InputPreset::VoiceRecognition: return "VoiceRecognition";
        case oboe::InputPreset::Generic: return "Generic";
        default: return "Other";
    }
}

PresetUsed toPresetUsed(oboe::InputPreset p) {
    switch (p) {
        case oboe::InputPreset::Unprocessed: return PresetUsed::Unprocessed;
        case oboe::InputPreset::VoiceRecognition: return PresetUsed::VoiceRecognition;
        default: return PresetUsed::Generic;
    }
}

} // namespace

AudioEngine& AudioEngine::instance() {
    static AudioEngine engine;
    return engine;
}

AudioEngine::~AudioEngine() {
    stop();
    close();
}

oboe::Result AudioEngine::openWithPreset(oboe::InputPreset preset, bool allocateSession,
                                         oboe::AudioFormat format, int32_t channels) {
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Input)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setSharingMode(oboe::SharingMode::Exclusive)
            ->setFormat(format)
            ->setSampleRate(kSampleRate)
            // No conversion wrappers. Oboe only falls back to them when the direct
            // open fails, and the direct open succeeds here - on the slow path. We
            // take whatever the device gives and convert it ourselves.
            ->setFormatConversionAllowed(false)
            ->setChannelConversionAllowed(false)
            ->setInputPreset(preset)
            // A session id lets Kotlin attach to AGC/NS/AEC and force them off, but it
            // also disqualifies the stream from the MMAP low-latency path. Which of
            // those matters more depends on the device, so it is a decision, not a
            // constant.
            ->setSessionId(allocateSession ? oboe::SessionId::Allocate : oboe::SessionId::None)
            ->setDataCallback(this)
            ->setErrorCallback(this);
    if (channels > 0) builder.setChannelCount(channels);

    std::shared_ptr<oboe::AudioStream> stream;
    const oboe::Result result = builder.openStream(stream);
    if (result != oboe::Result::OK) return result;

    stream_ = stream;
    lastPreset_ = preset;
    lastAllocateSession_ = allocateSession;
    presetUsed_ = toPresetUsed(preset);
    sessionId_ = stream_->getSessionId();
    mmapUsed_ = oboe::OboeExtensions::isMMapUsed(stream_.get());
    lowLatencyGranted_ = stream_->getPerformanceMode() == oboe::PerformanceMode::LowLatency;
    exclusiveGranted_ = stream_->getSharingMode() == oboe::SharingMode::Exclusive;
    sampleRate_ = stream_->getSampleRate();
    framesPerBurst_ = stream_->getFramesPerBurst();
    bufferCapacityFrames_ = stream_->getBufferCapacityInFrames();
    deviceFormat_ = stream_->getFormat();
    deviceChannels_ = std::max(1, stream_->getChannelCount());
    micChannel_ = 0;
    // Sized generously and allocated here, so the callback never has to.
    scratch_.assign(static_cast<size_t>(std::max(bufferCapacityFrames_, 4096)), 0.0f);

    // Two bursts is the low-latency starting point; the drain thread grows it if
    // the XRun counter climbs.
    const int32_t requested = framesPerBurst_ * 2;
    const auto sized = stream_->setBufferSizeInFrames(requested);
    const int32_t granted = sized ? sized.value() : stream_->getBufferSizeInFrames();
    if (!sized) {
        MM_LOGW("setBufferSizeInFrames(%d) failed: %s", requested,
                oboe::convertToText(sized.error()));
    }
    bufferSizeFrames_.store(granted, std::memory_order_relaxed);

    MM_LOGI("Opened input: preset=%s session=%s format=%s ch=%d rate=%d burst=%d "
            "buffer=%d/%d capacity=%d sessionId=%d api=%s mmap=%d lowLatency=%d exclusive=%d",
            presetName(preset), allocateSession ? "allocated" : "none",
            oboe::convertToText(deviceFormat_), deviceChannels_, sampleRate_,
            framesPerBurst_, granted, requested, bufferCapacityFrames_, sessionId_,
            oboe::convertToText(stream_->getAudioApi()),
            mmapUsed_ ? 1 : 0, lowLatencyGranted_ ? 1 : 0, exclusiveGranted_ ? 1 : 0);

    if (granted > requested) {
        // Not necessarily a problem for input: the callback still fires once per
        // burst, and the buffer is the overrun cushion rather than the latency. It
        // only matters if XRuns start appearing with no room left to grow.
        MM_LOGI("Buffer stayed at %d frames (asked %d, capacity %d); burst is %d, "
                "which is what sets the callback period.",
                granted, requested, bufferCapacityFrames_, framesPerBurst_);
    }

    return oboe::Result::OK;
}

/**
 * Opens the lowest-latency configuration this device will actually grant.
 *
 * Float mono is the convenient request, but plenty of devices only offer their
 * fast input path in 16-bit - asking for float gets a stream that works and is an
 * order of magnitude slower, with no error to say so. So: try float, and if the
 * burst that comes back is too large to be the fast path, try 16-bit and keep
 * whichever is smaller. The int16-to-float conversion then happens in our callback,
 * which costs one multiply per sample and keeps the small burst.
 */
oboe::Result AudioEngine::openBest(oboe::InputPreset preset, bool allocateSession) {
    // Anything above this is a legacy-path burst, not a low-latency one.
    constexpr int32_t kFastBurstThreshold = 256;

    oboe::Result result = openWithPreset(preset, allocateSession, oboe::AudioFormat::Float, kChannels);
    if (result == oboe::Result::OK) {
        if (framesPerBurst_ <= kFastBurstThreshold && lowLatencyGranted_) {
            return result;
        }
        const int32_t floatBurst = framesPerBurst_;
        MM_LOGI("Float gave burst=%d (lowLatency=%d); trying I16", floatBurst, lowLatencyGranted_ ? 1 : 0);
        stream_->close();
        stream_.reset();

        // Channel count unspecified: the fast path is often stereo-only, and we
        // would rather take two channels and pick one than be pushed back to legacy.
        const oboe::Result i16 = openWithPreset(preset, allocateSession, oboe::AudioFormat::I16, 0);
        if (i16 == oboe::Result::OK) {
            if (framesPerBurst_ < floatBurst) {
                MM_LOGI("I16 wins: burst %d -> %d (%.1f ms -> %.1f ms)",
                        floatBurst, framesPerBurst_,
                        1000.0 * floatBurst / sampleRate_,
                        1000.0 * framesPerBurst_ / sampleRate_);
                return i16;
            }
            stream_->close();
            stream_.reset();
        }
        // I16 was no better (or would not open); go back to float.
        return openWithPreset(preset, allocateSession, oboe::AudioFormat::Float, kChannels);
    }

    // Float would not open at all. I16 is the remaining option.
    return openWithPreset(preset, allocateSession, oboe::AudioFormat::I16, 0);
}

int32_t AudioEngine::open(bool allocateSession) {
    std::lock_guard<std::mutex> lg(lock_);
    if (stream_ != nullptr) return sessionId_;

    // Ask the platform for the MMAP path. Harmless where it is unsupported.
    if (oboe::OboeExtensions::isMMapSupported() && !oboe::OboeExtensions::isMMapEnabled()) {
        oboe::OboeExtensions::setMMapEnabled(true);
    }
    MM_LOGI("MMAP supported=%d enabled=%d",
            oboe::OboeExtensions::isMMapSupported() ? 1 : 0,
            oboe::OboeExtensions::isMMapEnabled() ? 1 : 0);

    // Unprocessed is the whole point. VoiceRecognition is the least-processed
    // fallback (AGC and NS are off on most devices); Generic is a last resort.
    const oboe::InputPreset presets[] = {
            oboe::InputPreset::Unprocessed,
            oboe::InputPreset::VoiceRecognition,
            oboe::InputPreset::Generic,
    };

    oboe::Result last = oboe::Result::ErrorInternal;
    for (const auto preset : presets) {
        last = openBest(preset, allocateSession);
        if (last == oboe::Result::OK) break;
        MM_LOGW("Preset %s failed: %s", presetName(preset), oboe::convertToText(last));
    }
    if (last != oboe::Result::OK) {
        MM_LOGE("All input presets failed: %s", oboe::convertToText(last));
        return static_cast<int32_t>(last);
    }

    ring_ = std::make_unique<RingBuffer<float>>(
            static_cast<size_t>(sampleRate_) * kChannels * kRingSeconds);
    rawRing_ = std::make_unique<RingBuffer<float>>(
            static_cast<size_t>(sampleRate_) * kChannels * kRingSeconds);

    // Everything the DSP chain needs is allocated here, on this thread, so the
    // audio callback never has to.
    noiseSuppressor_.prepare(static_cast<float>(sampleRate_), bufferCapacityFrames_);
    chain_.prepare(static_cast<float>(sampleRate_), bufferCapacityFrames_);
    chain_.setNoiseSuppressor(&noiseSuppressor_);
    resetStats();
    return 0;
}

bool AudioEngine::start() {
    {
        std::lock_guard<std::mutex> lg(lock_);
        if (stream_ == nullptr) return false;
        const oboe::Result result = stream_->requestStart();
        if (result != oboe::Result::OK) {
            MM_LOGE("requestStart failed: %s", oboe::convertToText(result));
            return false;
        }
        running_.store(true, std::memory_order_release);
    }

    if (!drainRunning_.exchange(true)) {
        drainThread_ = std::thread(&AudioEngine::drainLoop, this);
    }
    return true;
}

void AudioEngine::stop() {
    // Shut the drain thread down before touching the stream, so the drain thread
    // is never inside a restart while we are closing.
    if (drainRunning_.exchange(false) && drainThread_.joinable()) {
        drainThread_.join();
    }
    stopRecording();
    sender_.stop();

    std::lock_guard<std::mutex> lg(lock_);
    running_.store(false, std::memory_order_release);
    if (stream_ != nullptr) {
        stream_->requestStop();
    }
}

void AudioEngine::close() {
    std::lock_guard<std::mutex> lg(lock_);
    if (stream_ != nullptr) {
        stream_->close();
        stream_.reset();
    }
    presetUsed_ = PresetUsed::None;
    sessionId_ = -1;
}

// ---------------------------------------------------------------------------
// Audio callback. Real-time thread.
//
// Forbidden here: malloc/new/delete, locks, JNI, file or log I/O, std::string,
// allocating std::function, blocking syscalls. Allowed: arithmetic over
// preallocated buffers, atomics, and the lock-free ring write.
// ---------------------------------------------------------------------------
oboe::DataCallbackResult AudioEngine::onAudioReady(oboe::AudioStream* stream,
                                                   void* audioData,
                                                   int32_t numFrames) {
    enableFlushToZeroOnce();
    const int64_t t0 = nowNanos();

    // Normalise whatever the device handed us into mono float, in a buffer sized at
    // open time. Devices commonly grant the fast path only in 16-bit stereo, so this
    // conversion is the price of the small burst - one multiply per sample.
    const size_t count = static_cast<size_t>(numFrames);
    if (scratch_.size() < count) {
        // Cannot grow here: allocating on the audio thread is exactly what causes the
        // glitches this whole design avoids. Drop the block and count it instead.
        framesDropped_.fetch_add(numFrames, std::memory_order_relaxed);
        return oboe::DataCallbackResult::Continue;
    }
    float* in = scratch_.data();

    if (deviceFormat_ == oboe::AudioFormat::I16) {
        const auto* src = static_cast<const int16_t*>(audioData);
        constexpr float kScale = 1.0f / 32768.0f;
        for (size_t i = 0; i < count; ++i) {
            in[i] = static_cast<float>(src[i * deviceChannels_ + micChannel_]) * kScale;
        }
    } else {
        const auto* src = static_cast<const float*>(audioData);
        for (size_t i = 0; i < count; ++i) {
            in[i] = src[i * deviceChannels_ + micChannel_];
        }
    }

    // Input metering happens before the chain, so the meter shows what the capsule
    // delivered rather than what the compressor made of it.
    float peak = 0.0f;
    float sumSquares = 0.0f;
    for (size_t i = 0; i < count; ++i) {
        const float s = in[i];
        const float a = std::fabs(s);
        if (a > peak) peak = a;
        sumSquares += s * s;
    }

    peak_.store(peak, std::memory_order_relaxed);
    rms_.store(count > 0 ? std::sqrt(sumSquares / static_cast<float>(count)) : 0.0f,
               std::memory_order_relaxed);
    callbackFrames_.store(numFrames, std::memory_order_relaxed);
    framesCaptured_.fetch_add(numFrames, std::memory_order_relaxed);

    // Raw tap, taken before anything touches the samples. Only filled while a raw
    // recording is armed, so it costs nothing the rest of the time.
    if (rawRing_ != nullptr && recording_.load(std::memory_order_relaxed) &&
        recordSource_.load(std::memory_order_relaxed) == RecordSource::Raw) {
        rawRing_->write(in, count);
    }

    // Processed in place. The parameter block is acquire-loaded once per callback.
    chain_.process(in, numFrames, params_.current());

    if (ring_ != nullptr && !ring_->write(in, count)) {
        // Consumer fell behind. Count it rather than blocking.
        framesDropped_.fetch_add(numFrames, std::memory_order_relaxed);
    }

    // Worst-case callback cost as a fraction of its deadline. This is the number
    // that tells us how much room the Phase 3 DSP chain has.
    const double deadlineNs =
            1e9 * static_cast<double>(numFrames) / static_cast<double>(stream->getSampleRate());
    const float load = static_cast<float>(static_cast<double>(nowNanos() - t0) / deadlineNs);
    if (load > callbackLoad_.load(std::memory_order_relaxed)) {
        callbackLoad_.store(load, std::memory_order_relaxed);
    }

    return oboe::DataCallbackResult::Continue;
}

void AudioEngine::onErrorAfterClose(oboe::AudioStream* /*stream*/, oboe::Result error) {
    // Fires on headset or USB mic hotplug and on some Bluetooth transitions.
    // Flag it; the drain thread does the rebuild so we never block this callback.
    MM_LOGW("Stream error after close: %s - scheduling restart", oboe::convertToText(error));
    needsRestart_.store(true, std::memory_order_release);
}

// ---------------------------------------------------------------------------
// Drain thread.
// ---------------------------------------------------------------------------
void AudioEngine::drainLoop() {
    std::vector<float> chunk(kDrainChunkFrames * kChannels);
    std::vector<float> rawChunk(kDrainChunkFrames * kChannels);
    auto lastTune = std::chrono::steady_clock::now();
    lastTunedXRuns_ = 0;

    while (drainRunning_.load(std::memory_order_acquire)) {
        if (needsRestart_.exchange(false)) {
            std::lock_guard<std::mutex> lg(lock_);
            if (stream_ != nullptr) {
                stream_->close();
                stream_.reset();
            }
            if (openBest(lastPreset_, lastAllocateSession_) == oboe::Result::OK) {
                if (stream_->requestStart() == oboe::Result::OK) {
                    MM_LOGI("Stream restarted after disconnect");
                } else {
                    MM_LOGE("Restart: requestStart failed");
                }
            } else {
                MM_LOGE("Restart: reopen failed");
            }
        }

        // Arm or disarm WAV capture on this thread, never in the callback.
        if (recordRequested_.load(std::memory_order_acquire) != recording_.load(std::memory_order_acquire)) {
            std::lock_guard<std::mutex> lg(recordLock_);
            if (recordRequested_.load(std::memory_order_acquire)) {
                if (writer_.open(pendingRecordPath_, sampleRate_, kChannels)) {
                    recordedFrames_.store(0, std::memory_order_relaxed);
                    recording_.store(true, std::memory_order_release);
                } else {
                    recordRequested_.store(false, std::memory_order_release);
                }
            } else {
                writer_.close();
                recording_.store(false, std::memory_order_release);
            }
        }

        size_t got = 0;
        if (ring_ != nullptr) {
            got = ring_->read(chunk.data(), chunk.size());
        }
        if (got > 0) {
            // Network first, disk second: a WAV write must never delay a packet.
            sender_.setDspEnabled(params_.current().enabled);
            sender_.submit(chunk.data(), got);

            const bool recordingProcessed =
                    recording_.load(std::memory_order_acquire) &&
                    recordSource_.load(std::memory_order_relaxed) == RecordSource::Processed;
            if (recordingProcessed) {
                std::lock_guard<std::mutex> lg(recordLock_);
                if (writer_.isOpen()) {
                    writer_.write(chunk.data(), got);
                    recordedFrames_.store(static_cast<int64_t>(writer_.framesWritten()),
                                          std::memory_order_relaxed);
                }
            }
        }

        if (recording_.load(std::memory_order_acquire) &&
            recordSource_.load(std::memory_order_relaxed) == RecordSource::Raw &&
            rawRing_ != nullptr) {
            const size_t rawGot = rawRing_->read(rawChunk.data(), rawChunk.size());
            if (rawGot > 0) {
                std::lock_guard<std::mutex> lg(recordLock_);
                if (writer_.isOpen()) {
                    writer_.write(rawChunk.data(), rawGot);
                    recordedFrames_.store(static_cast<int64_t>(writer_.framesWritten()),
                                          std::memory_order_relaxed);
                }
            }
            got += rawGot;
        }

        if (got == 0) {
            std::this_thread::sleep_for(std::chrono::milliseconds(4));
        }

        const auto now = std::chrono::steady_clock::now();
        if (now - lastTune > std::chrono::seconds(1)) {
            lastTune = now;
            tuneBufferSize();
        }
    }

    std::lock_guard<std::mutex> lg(recordLock_);
    writer_.close();
    recording_.store(false, std::memory_order_release);
}

void AudioEngine::tuneBufferSize() {
    std::shared_ptr<oboe::AudioStream> stream;
    {
        std::lock_guard<std::mutex> lg(lock_);
        stream = stream_;
    }
    if (stream == nullptr) return;

    // For an input stream the buffer size is an overrun cushion, not the latency:
    // the callback fires once per burst regardless. Ask the stream what its actual
    // latency is rather than inferring it from a number that does not mean that.
    const auto latency = stream->calculateLatencyMillis();
    if (latency) {
        measuredLatencyMs_.store(static_cast<float>(latency.value()), std::memory_order_relaxed);
    }

    const auto xruns = stream->getXRunCount();
    if (!xruns) return;
    xRunCount_.store(xruns.value(), std::memory_order_relaxed);

    if (xruns.value() > lastTunedXRuns_) {
        lastTunedXRuns_ = xruns.value();
        const int32_t current = stream->getBufferSizeInFrames();
        const int32_t target = current + framesPerBurst_;
        if (target <= framesPerBurst_ * kMaxBufferBursts && target <= bufferCapacityFrames_) {
            const auto sized = stream->setBufferSizeInFrames(target);
            if (sized) {
                bufferSizeFrames_.store(sized.value(), std::memory_order_relaxed);
                MM_LOGI("XRuns=%d - buffer grown to %d frames", xruns.value(), sized.value());
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Recording control (any thread).
// ---------------------------------------------------------------------------
bool AudioEngine::startRecording(const std::string& path, RecordSource source) {
    if (!running_.load(std::memory_order_acquire)) return false;
    {
        std::lock_guard<std::mutex> lg(recordLock_);
        pendingRecordPath_ = path;
    }
    recordSource_.store(source, std::memory_order_relaxed);
    if (rawRing_ != nullptr) rawRing_->reset();
    recordRequested_.store(true, std::memory_order_release);
    return true;
}

void AudioEngine::stopRecording() {
    recordRequested_.store(false, std::memory_order_release);
}

std::string AudioEngine::recordingPath() {
    std::lock_guard<std::mutex> lg(recordLock_);
    return writer_.isOpen() ? writer_.path() : pendingRecordPath_;
}

// ---------------------------------------------------------------------------
// Transport control (any thread).
// ---------------------------------------------------------------------------
bool AudioEngine::setTarget(const std::string& host, uint16_t port) {
    return sender_.setTarget(host, port);
}

bool AudioEngine::startStreaming(int32_t framesPerPacket, int32_t wireFormat) {
    if (!running_.load(std::memory_order_acquire)) return false;
    sender_.setFormat(static_cast<packet::SampleFormat>(wireFormat));
    return sender_.start(sampleRate_, kChannels, framesPerPacket);
}

void AudioEngine::stopStreaming() {
    sender_.stop();
}

void AudioEngine::probePaths() {
    struct Probe {
        const char* label;
        oboe::InputPreset preset;
        int32_t channels;        // 0 = unspecified
        int32_t sampleRate;      // 0 = unspecified
        oboe::AudioFormat format;
        oboe::SharingMode sharing;
        bool allowConversion;
        bool allocateSession;
    };

    const Probe probes[] = {
        {"current config",        oboe::InputPreset::Unprocessed,      1, 48000, oboe::AudioFormat::Float, oboe::SharingMode::Exclusive, true,  true},
        {"no session",            oboe::InputPreset::Unprocessed,      1, 48000, oboe::AudioFormat::Float, oboe::SharingMode::Exclusive, true,  false},
        {"no conversion",         oboe::InputPreset::Unprocessed,      1, 48000, oboe::AudioFormat::Float, oboe::SharingMode::Exclusive, false, false},
        {"unspecified rate/ch",   oboe::InputPreset::Unprocessed,      0,     0, oboe::AudioFormat::Float, oboe::SharingMode::Exclusive, false, false},
        {"unspecified + i16",     oboe::InputPreset::Unprocessed,      0,     0, oboe::AudioFormat::I16,   oboe::SharingMode::Exclusive, false, false},
        {"stereo 48k",            oboe::InputPreset::Unprocessed,      2, 48000, oboe::AudioFormat::Float, oboe::SharingMode::Exclusive, false, false},
        {"shared mode",           oboe::InputPreset::Unprocessed,      0,     0, oboe::AudioFormat::Float, oboe::SharingMode::Shared,    false, false},
        {"VoiceRecognition",      oboe::InputPreset::VoiceRecognition, 0,     0, oboe::AudioFormat::Float, oboe::SharingMode::Exclusive, false, false},
        {"Generic",               oboe::InputPreset::Generic,          0,     0, oboe::AudioFormat::Float, oboe::SharingMode::Exclusive, false, false},
        {"VoiceCommunication",    oboe::InputPreset::VoiceCommunication,0,    0, oboe::AudioFormat::Float, oboe::SharingMode::Exclusive, false, false},
        {"Camcorder",             oboe::InputPreset::Camcorder,        0,     0, oboe::AudioFormat::Float, oboe::SharingMode::Exclusive, false, false},
    };

    MM_LOGI("---- input path probe ----");
    MM_LOGI("%-22s %6s %5s %4s %6s %5s %5s %4s", "config", "burst", "rate", "ch", "cap", "mmap", "lowLat", "excl");

    for (const auto& probe : probes) {
        oboe::AudioStreamBuilder builder;
        builder.setDirection(oboe::Direction::Input)
                ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
                ->setSharingMode(probe.sharing)
                ->setFormat(probe.format)
                ->setInputPreset(probe.preset)
                ->setSessionId(probe.allocateSession ? oboe::SessionId::Allocate
                                                     : oboe::SessionId::None);
        if (probe.channels > 0) builder.setChannelCount(probe.channels);
        if (probe.sampleRate > 0) builder.setSampleRate(probe.sampleRate);
        builder.setFormatConversionAllowed(probe.allowConversion)
                ->setChannelConversionAllowed(probe.allowConversion);
        if (probe.allowConversion) {
            builder.setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium);
        }

        std::shared_ptr<oboe::AudioStream> stream;
        const oboe::Result result = builder.openStream(stream);
        if (result != oboe::Result::OK) {
            MM_LOGI("%-22s FAILED: %s", probe.label, oboe::convertToText(result));
            continue;
        }

        MM_LOGI("%-22s %6d %5d %4d %6d %5d %6d %4d",
                probe.label,
                stream->getFramesPerBurst(),
                stream->getSampleRate(),
                stream->getChannelCount(),
                stream->getBufferCapacityInFrames(),
                oboe::OboeExtensions::isMMapUsed(stream.get()) ? 1 : 0,
                stream->getPerformanceMode() == oboe::PerformanceMode::LowLatency ? 1 : 0,
                stream->getSharingMode() == oboe::SharingMode::Exclusive ? 1 : 0);
        stream->close();
    }
    MM_LOGI("---- probe done ----");
}

void AudioEngine::resetStats() {
    xRunCount_.store(0, std::memory_order_relaxed);
    framesCaptured_.store(0, std::memory_order_relaxed);
    framesDropped_.store(0, std::memory_order_relaxed);
    peak_.store(0.0f, std::memory_order_relaxed);
    rms_.store(0.0f, std::memory_order_relaxed);
    callbackLoad_.store(0.0f, std::memory_order_relaxed);
    measuredLatencyMs_.store(0.0f, std::memory_order_relaxed);
    lastTunedXRuns_ = 0;
}

} // namespace mobimic
