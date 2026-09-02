#pragma once

#include <cstdint>
#include <cstring>

namespace mobimic {

/**
 * Wire format, version 1.
 *
 * Layout is fixed and little-endian. Mirrored by HEADER_FORMAT in pc/receiver.py.
 *
 *   offset size field
 *   0      4    magic 'MMIC'
 *   4      1    version
 *   5      1    flags        bits 0-1 sample format, bit 2 DSP enabled,
 *                             bit 3 sent over a USB tether
 *   6      1    channels
 *   7      1    reserved
 *   8      4    seq          increments per packet, wraps
 *   12     8    frameIndex   monotonic capture frame counter
 *   20     4    sampleRate
 *   24     2    numFrames
 *   26     2    reserved
 *
 * frameIndex is the receiver's drift reference: it says exactly how many frames
 * the capture side has produced, independent of how many packets arrived. Without
 * it the receiver cannot tell a clock offset from packet loss.
 */
namespace packet {

constexpr size_t kHeaderBytes = 28;
constexpr uint8_t kVersion = 1;
constexpr char kMagic[4] = {'M', 'M', 'I', 'C'};

enum class SampleFormat : uint8_t {
    PcmS16 = 0,
    PcmF32 = 1,
    Opus = 2,
};

/** Bytes on the wire for one frame in this format. */
inline size_t frameBytes(SampleFormat format, int channels) {
    switch (format) {
        case SampleFormat::PcmS16: return 2u * static_cast<size_t>(channels);
        case SampleFormat::PcmF32: return 4u * static_cast<size_t>(channels);
        case SampleFormat::Opus: return 0; // variable, Phase 6
    }
    return 0;
}

inline void put16(uint8_t* p, uint16_t v) {
    p[0] = static_cast<uint8_t>(v);
    p[1] = static_cast<uint8_t>(v >> 8);
}

inline void put32(uint8_t* p, uint32_t v) {
    p[0] = static_cast<uint8_t>(v);
    p[1] = static_cast<uint8_t>(v >> 8);
    p[2] = static_cast<uint8_t>(v >> 16);
    p[3] = static_cast<uint8_t>(v >> 24);
}

inline void put64(uint8_t* p, uint64_t v) {
    for (int i = 0; i < 8; ++i) p[i] = static_cast<uint8_t>(v >> (8 * i));
}

inline void writeHeader(uint8_t* dst,
                        SampleFormat format,
                        bool dspEnabled,
                        bool overUsb,
                        uint8_t channels,
                        uint32_t seq,
                        uint64_t frameIndex,
                        uint32_t sampleRate,
                        uint16_t numFrames) {
    std::memcpy(dst, kMagic, 4);
    dst[4] = kVersion;
    // The sender knows which interface it used; the receiver cannot reliably infer
    // it. Tether subnets are vendor-specific - this phone uses 10.194.134.x, not the
    // 192.168.42.x that Android documents - so guessing from the address is wrong.
    dst[5] = static_cast<uint8_t>(static_cast<uint8_t>(format) |
                                  (dspEnabled ? 0x04u : 0u) |
                                  (overUsb ? 0x08u : 0u));
    dst[6] = channels;
    dst[7] = 0;
    put32(dst + 8, seq);
    put64(dst + 12, frameIndex);
    put32(dst + 20, sampleRate);
    put16(dst + 24, numFrames);
    put16(dst + 26, 0);
}

} // namespace packet
} // namespace mobimic
