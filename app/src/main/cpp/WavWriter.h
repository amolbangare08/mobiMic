#pragma once

#include <cstdint>
#include <cstdio>
#include <string>

namespace mobimic {

/**
 * Minimal 32-bit IEEE float WAV writer.
 *
 * Float rather than int16 on purpose: Phase 1 exists to prove the capture path is
 * untouched, so the verification file must not add a quantisation stage of its own.
 *
 * Called only from the drain thread, never from the audio callback.
 */
class WavWriter {
public:
    ~WavWriter();

    bool open(const std::string& path, int32_t sampleRate, int32_t channels);
    bool write(const float* samples, size_t count);
    void close();

    bool isOpen() const { return file_ != nullptr; }
    uint64_t framesWritten() const { return framesWritten_; }
    const std::string& path() const { return path_; }

private:
    void writeHeader();
    void patchHeader();

    FILE* file_ = nullptr;
    std::string path_;
    int32_t sampleRate_ = 48000;
    int32_t channels_ = 1;
    uint64_t framesWritten_ = 0;
};

} // namespace mobimic
