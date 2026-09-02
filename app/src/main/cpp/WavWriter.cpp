#include "WavWriter.h"

#include "Common.h"

#include <cstring>

namespace mobimic {

namespace {

constexpr long kHeaderBytes = 58;

void put32(uint8_t* p, uint32_t v) {
    p[0] = static_cast<uint8_t>(v);
    p[1] = static_cast<uint8_t>(v >> 8);
    p[2] = static_cast<uint8_t>(v >> 16);
    p[3] = static_cast<uint8_t>(v >> 24);
}

void put16(uint8_t* p, uint16_t v) {
    p[0] = static_cast<uint8_t>(v);
    p[1] = static_cast<uint8_t>(v >> 8);
}

} // namespace

WavWriter::~WavWriter() {
    close();
}

bool WavWriter::open(const std::string& path, int32_t sampleRate, int32_t channels) {
    close();
    file_ = std::fopen(path.c_str(), "wb");
    if (file_ == nullptr) {
        MM_LOGE("WavWriter: cannot open %s", path.c_str());
        return false;
    }
    path_ = path;
    sampleRate_ = sampleRate;
    channels_ = channels;
    framesWritten_ = 0;
    writeHeader();
    return true;
}

void WavWriter::writeHeader() {
    uint8_t h[kHeaderBytes] = {};
    const uint16_t bitsPerSample = 32;
    const uint16_t blockAlign = static_cast<uint16_t>(channels_ * bitsPerSample / 8);
    const uint32_t byteRate = static_cast<uint32_t>(sampleRate_) * blockAlign;

    std::memcpy(h + 0, "RIFF", 4);
    put32(h + 4, 0); // patched on close
    std::memcpy(h + 8, "WAVE", 4);

    std::memcpy(h + 12, "fmt ", 4);
    put32(h + 16, 18);                 // non-PCM formats need the cbSize field
    put16(h + 20, 3);                  // WAVE_FORMAT_IEEE_FLOAT
    put16(h + 22, static_cast<uint16_t>(channels_));
    put32(h + 24, static_cast<uint32_t>(sampleRate_));
    put32(h + 28, byteRate);
    put16(h + 32, blockAlign);
    put16(h + 34, bitsPerSample);
    put16(h + 36, 0);                  // cbSize

    std::memcpy(h + 38, "fact", 4);
    put32(h + 42, 4);
    put32(h + 46, 0);                  // patched on close

    std::memcpy(h + 50, "data", 4);
    put32(h + 54, 0);                  // patched on close

    std::fwrite(h, 1, kHeaderBytes, file_);
}

bool WavWriter::write(const float* samples, size_t count) {
    if (file_ == nullptr || count == 0) return false;
    const size_t written = std::fwrite(samples, sizeof(float), count, file_);
    framesWritten_ += written / static_cast<size_t>(channels_);
    return written == count;
}

void WavWriter::patchHeader() {
    const uint32_t dataBytes =
            static_cast<uint32_t>(framesWritten_ * static_cast<uint64_t>(channels_) * sizeof(float));
    uint8_t v[4];

    std::fseek(file_, 4, SEEK_SET);
    put32(v, static_cast<uint32_t>(kHeaderBytes - 8 + dataBytes));
    std::fwrite(v, 1, 4, file_);

    std::fseek(file_, 46, SEEK_SET);
    put32(v, static_cast<uint32_t>(framesWritten_));
    std::fwrite(v, 1, 4, file_);

    std::fseek(file_, 54, SEEK_SET);
    put32(v, dataBytes);
    std::fwrite(v, 1, 4, file_);
}

void WavWriter::close() {
    if (file_ == nullptr) return;
    patchHeader();
    std::fclose(file_);
    file_ = nullptr;
    MM_LOGI("WavWriter: closed %s (%llu frames)", path_.c_str(),
            static_cast<unsigned long long>(framesWritten_));
}

} // namespace mobimic
