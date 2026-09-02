#include "UdpSender.h"

#include "../Common.h"

#include <arpa/inet.h>
#include <fcntl.h>
#include <netdb.h>
#include <sys/socket.h>
#include <unistd.h>

#include <algorithm>
#include <cerrno>
#include <cstring>
#include <cmath>
#include <cstdio>

namespace mobimic {

namespace {
constexpr int kSendBufferBytes = 1 << 19; // 512 KiB
}

UdpSender::~UdpSender() {
    stop();
}

bool UdpSender::setTarget(const std::string& host, uint16_t port) {
    addrinfo hints{};
    hints.ai_family = AF_INET;      // IPv4 only for now; the receiver binds v4.
    hints.ai_socktype = SOCK_DGRAM;

    addrinfo* result = nullptr;
    const int rc = getaddrinfo(host.c_str(), nullptr, &hints, &result);
    if (rc != 0 || result == nullptr) {
        MM_LOGE("Cannot resolve %s: %s", host.c_str(), gai_strerror(rc));
        return false;
    }

    sockaddr_in resolved = *reinterpret_cast<sockaddr_in*>(result->ai_addr);
    resolved.sin_port = htons(port);
    freeaddrinfo(result);

    {
        std::lock_guard<std::mutex> lg(targetLock_);
        target_ = resolved;
        hasTarget_ = true;
        targetHost_ = host;
        targetPort_ = port;
    }
    MM_LOGI("Target set to %s:%u", host.c_str(), port);
    return true;
}

void UdpSender::setLocalAddress(const std::string& address) {
    std::lock_guard<std::mutex> lg(targetLock_);
    localAddress_ = address;
}

bool UdpSender::start(int32_t sampleRate, int32_t channels, int32_t framesPerPacket) {
    {
        std::lock_guard<std::mutex> lg(targetLock_);
        if (!hasTarget_) {
            MM_LOGE("Cannot start sender: no target set");
            return false;
        }
    }
    if (streaming_.load(std::memory_order_acquire)) return true;

    socket_ = ::socket(AF_INET, SOCK_DGRAM, 0);
    if (socket_ < 0) {
        MM_LOGE("socket() failed: %s", strerror(errno));
        return false;
    }

    const int sndBuf = kSendBufferBytes;
    setsockopt(socket_, SOL_SOCKET, SO_SNDBUF, &sndBuf, sizeof(sndBuf));

    std::string local;
    {
        std::lock_guard<std::mutex> lg(targetLock_);
        local = localAddress_;
    }
    if (!local.empty()) {
        sockaddr_in source{};
        source.sin_family = AF_INET;
        source.sin_port = 0; // any ephemeral port
        if (inet_pton(AF_INET, local.c_str(), &source.sin_addr) == 1) {
            if (::bind(socket_, reinterpret_cast<sockaddr*>(&source), sizeof(source)) == 0) {
                MM_LOGI("Sending from local address %s", local.c_str());
            } else {
                // Not fatal: the stream still works, it just may take the other route.
                MM_LOGW("Could not bind to %s: %s", local.c_str(), strerror(errno));
            }
        } else {
            MM_LOGW("Local address %s is not a valid IPv4 literal", local.c_str());
        }
    }

    // Non-blocking: a stalled network must never stall the drain thread.
    const int flags = fcntl(socket_, F_GETFL, 0);
    fcntl(socket_, F_SETFL, flags | O_NONBLOCK);

    sampleRate_ = sampleRate;
    channels_ = channels;
    framesPerPacket_ = framesPerPacket;

    pending_.assign(static_cast<size_t>(framesPerPacket_) * channels_, 0.0f);
    pendingFrames_ = 0;
    packetBuffer_.assign(
            packet::kHeaderBytes + static_cast<size_t>(framesPerPacket_) * channels_ * sizeof(float),
            0);
    seq_ = 0;
    frameIndex_ = 0;
    resetStats();

    streaming_.store(true, std::memory_order_release);
    MM_LOGI("Sender started: %d Hz, %d ch, %d frames/packet", sampleRate_, channels_, framesPerPacket_);
    return true;
}

void UdpSender::stop() {
    streaming_.store(false, std::memory_order_release);
    if (socket_ >= 0) {
        ::close(socket_);
        socket_ = -1;
    }
    pendingFrames_ = 0;
}

void UdpSender::setFormat(packet::SampleFormat format) {
    format_.store(format, std::memory_order_relaxed);
}

std::string UdpSender::targetDescription() {
    std::lock_guard<std::mutex> lg(targetLock_);
    if (!hasTarget_) return "";
    char buffer[64];
    std::snprintf(buffer, sizeof(buffer), "%s:%u", targetHost_.c_str(), targetPort_);
    return buffer;
}

void UdpSender::resetStats() {
    packetsSent_.store(0, std::memory_order_relaxed);
    bytesSent_.store(0, std::memory_order_relaxed);
    sendErrors_.store(0, std::memory_order_relaxed);
}

/**
 * TPDF dither, one LSB peak-to-peak.
 *
 * Without it, truncating float to int16 correlates the quantisation error with the
 * signal, which is audible as grit on quiet passages - exactly where a broadcast
 * voice chain spends most of its time.
 */
int32_t UdpSender::ditheredS16(float sample) {
    // xorshift32, cheap and good enough for dither noise.
    ditherState_ ^= ditherState_ << 13;
    ditherState_ ^= ditherState_ >> 17;
    ditherState_ ^= ditherState_ << 5;
    const float r1 = static_cast<float>(ditherState_ & 0xFFFF) / 65535.0f;
    ditherState_ ^= ditherState_ << 13;
    ditherState_ ^= ditherState_ >> 17;
    ditherState_ ^= ditherState_ << 5;
    const float r2 = static_cast<float>(ditherState_ & 0xFFFF) / 65535.0f;

    const float dither = (r1 - r2); // triangular, +-1 LSB
    const float scaled = sample * 32767.0f + dither;
    return std::max(-32768, std::min(32767, static_cast<int32_t>(std::lrintf(scaled))));
}

void UdpSender::submit(const float* samples, size_t count) {
    if (!streaming_.load(std::memory_order_acquire)) return;

    const size_t packetSamples = static_cast<size_t>(framesPerPacket_) * channels_;
    size_t offset = 0;
    while (offset < count) {
        const size_t room = packetSamples - pendingFrames_ * channels_;
        const size_t take = std::min(room, count - offset);
        std::copy(samples + offset, samples + offset + take,
                  pending_.begin() + static_cast<long>(pendingFrames_ * channels_));
        pendingFrames_ += take / static_cast<size_t>(channels_);
        offset += take;

        if (pendingFrames_ >= static_cast<size_t>(framesPerPacket_)) {
            flushPacket();
        }
    }
}

void UdpSender::flushPacket() {
    const auto format = format_.load(std::memory_order_relaxed);
    const size_t frames = pendingFrames_;
    const size_t samples = frames * static_cast<size_t>(channels_);

    uint8_t* payload = packetBuffer_.data() + packet::kHeaderBytes;
    size_t payloadBytes = 0;

    if (format == packet::SampleFormat::PcmF32) {
        std::memcpy(payload, pending_.data(), samples * sizeof(float));
        payloadBytes = samples * sizeof(float);
    } else {
        for (size_t i = 0; i < samples; ++i) {
            const int32_t v = ditheredS16(pending_[i]);
            payload[i * 2] = static_cast<uint8_t>(v & 0xFF);
            payload[i * 2 + 1] = static_cast<uint8_t>((v >> 8) & 0xFF);
        }
        payloadBytes = samples * 2;
    }

    packet::writeHeader(packetBuffer_.data(), format,
                        dspEnabled_.load(std::memory_order_relaxed),
                        overUsb_.load(std::memory_order_relaxed),
                        static_cast<uint8_t>(channels_), seq_, frameIndex_,
                        static_cast<uint32_t>(sampleRate_), static_cast<uint16_t>(frames));

    sockaddr_in destination{};
    {
        std::lock_guard<std::mutex> lg(targetLock_);
        if (!hasTarget_) {
            pendingFrames_ = 0;
            return;
        }
        destination = target_;
    }

    const ssize_t sent = ::sendto(socket_, packetBuffer_.data(),
                                  packet::kHeaderBytes + payloadBytes, 0,
                                  reinterpret_cast<sockaddr*>(&destination), sizeof(destination));
    if (sent < 0) {
        sendErrors_.fetch_add(1, std::memory_order_relaxed);
    } else {
        packetsSent_.fetch_add(1, std::memory_order_relaxed);
        bytesSent_.fetch_add(sent, std::memory_order_relaxed);
    }

    seq_++;
    frameIndex_ += frames;
    pendingFrames_ = 0;
}

} // namespace mobimic
