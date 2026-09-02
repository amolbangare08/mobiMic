#pragma once

#include <atomic>
#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

#include <netinet/in.h>

#include "Packet.h"

namespace mobimic {

/**
 * Packetises captured audio and pushes it out over UDP.
 *
 * submit() is called from the drain thread, never from the audio callback. The
 * socket is non-blocking: a send that would block is counted as an error and
 * dropped, because a microphone stream that waits is worse than one that gaps.
 */
class UdpSender {
public:
    UdpSender() = default;
    ~UdpSender();

    /** Resolves and stores the destination. Safe to call while streaming. */
    bool setTarget(const std::string& host, uint16_t port);

    /**
     * Binds the socket to a specific local address before sending.
     *
     * With USB tethering active the phone has two routes to the world, and the
     * kernel picks by routing table, not by what we intended. Binding to the USB
     * interface's own address is what guarantees the audio leaves over the cable
     * rather than quietly going back out over Wi-Fi.
     *
     * Empty string clears it and lets the system route normally.
     */
    void setLocalAddress(const std::string& address);

    bool start(int32_t sampleRate, int32_t channels, int32_t framesPerPacket);
    void stop();

    bool isStreaming() const { return streaming_.load(std::memory_order_acquire); }

    void setFormat(packet::SampleFormat format);
    packet::SampleFormat format() const { return format_.load(std::memory_order_relaxed); }

    /** Feeds interleaved float samples. Sends whenever a full packet has accumulated. */
    void submit(const float* samples, size_t count);

    /** Recorded in the packet header so the receiver knows what it is getting. */
    void setDspEnabled(bool enabled) { dspEnabled_.store(enabled, std::memory_order_relaxed); }

    /** Tells the receiver this stream is coming over a cable, not Wi-Fi. */
    void setOverUsb(bool overUsb) { overUsb_.store(overUsb, std::memory_order_relaxed); }

    int64_t packetsSent() const { return packetsSent_.load(std::memory_order_relaxed); }
    int64_t bytesSent() const { return bytesSent_.load(std::memory_order_relaxed); }
    int64_t sendErrors() const { return sendErrors_.load(std::memory_order_relaxed); }
    std::string targetDescription();

    void resetStats();

private:
    void flushPacket();
    int32_t ditheredS16(float sample);

    std::mutex targetLock_;
    sockaddr_in target_{};
    std::string localAddress_;
    bool hasTarget_ = false;
    std::string targetHost_;
    uint16_t targetPort_ = 0;

    int socket_ = -1;
    std::atomic<bool> streaming_{false};
    std::atomic<packet::SampleFormat> format_{packet::SampleFormat::PcmS16};
    std::atomic<bool> dspEnabled_{false};
    std::atomic<bool> overUsb_{false};

    int32_t sampleRate_ = 48000;
    int32_t channels_ = 1;
    int32_t framesPerPacket_ = 240;

    std::vector<float> pending_;   // interleaved float accumulator
    size_t pendingFrames_ = 0;
    std::vector<uint8_t> packetBuffer_;

    uint32_t seq_ = 0;
    uint64_t frameIndex_ = 0;
    uint32_t ditherState_ = 0x1234567u;

    std::atomic<int64_t> packetsSent_{0};
    std::atomic<int64_t> bytesSent_{0};
    std::atomic<int64_t> sendErrors_{0};
};

} // namespace mobimic
