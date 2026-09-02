"""
mobiMic Windows receiver - Phase 2.

Takes the phone's UDP audio stream and plays it into a virtual audio cable, so
Windows sees it as an ordinary recording device.

Three parts, deliberately separate:

  * Receiver thread   - blocking recvfrom, header parse, reorder, hand to the ring.
  * Jitter buffer     - absorbs network jitter, conceals loss, reports fill level.
  * Output callback   - PortAudio pulls from the ring through a variable-ratio
                        resampler that cancels the phone-to-PC clock drift.

The drift compensation is the part that makes this run for hours instead of
minutes. The phone's audio clock and the PC's differ by 10-100 ppm, which is a
couple of samples per second; without correction the buffer starves or overflows.

Usage:
    python receiver.py --list-devices
    python receiver.py --device "CABLE Input"
"""

from __future__ import annotations

import argparse
import gc
import json
import os
import socket
import struct
import sys
import threading
import time
from dataclasses import dataclass, field

import numpy as np

try:
    import sounddevice as sd
except ImportError:  # pragma: no cover - dependency hint
    sd = None

try:
    from zeroconf import ServiceInfo, Zeroconf
except ImportError:  # optional: discovery just stays off without it
    ServiceInfo = None
    Zeroconf = None

# Mirrors packet::writeHeader in app/src/main/cpp/net/Packet.h
HEADER_FORMAT = "<4sBBBBIQIHH"
HEADER_SIZE = struct.calcsize(HEADER_FORMAT)
MAGIC = b"MMIC"

FORMAT_S16 = 0
FORMAT_F32 = 1
FORMAT_OPUS = 2

DEFAULT_PORT = 47001
DEFAULT_SAMPLE_RATE = 48000

# Discovery, so the phone never has to be told an IP address. Deliberately tiny and
# separate from mDNS: a USB tether brings its subnet up and down with the cable, and
# a one-shot broadcast probe copes with that better than a service registry does.
DISCOVERY_PORT = 47002
PROBE_MAGIC = b"MMICPROB"
REPLY_MAGIC = b"MMICHERE"
DISCOVERY_VERSION = 1

# Bit 3 of the header flags: the sender used a USB tether. This is asserted by the
# sender rather than inferred here, because tether subnets are vendor-specific -
# the phone this was developed against uses 10.194.134.x, not the 192.168.42.x that
# Android documents, so guessing from the source address is simply wrong.
FLAG_DSP = 0x04
FLAG_USB = 0x08

# Remembers what each output device turned out to need, so the buffer target is
# learned once rather than rediscovered through the same minute of underruns on
# every launch.
STATE_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "receiver_state.json")


def load_learned_target(device_name: str):
    try:
        with open(STATE_PATH, "r", encoding="utf-8") as handle:
            return json.load(handle).get("targets", {}).get(device_name)
    except (OSError, ValueError):
        return None


def save_learned_target(device_name: str, target_ms: float) -> None:
    state = {"targets": {}}
    try:
        with open(STATE_PATH, "r", encoding="utf-8") as handle:
            state = json.load(handle)
            state.setdefault("targets", {})
    except (OSError, ValueError):
        pass
    state["targets"][device_name] = round(float(target_ms), 1)
    try:
        with open(STATE_PATH, "w", encoding="utf-8") as handle:
            json.dump(state, handle, indent=2)
    except OSError:
        pass


# ---------------------------------------------------------------------------
# Packet parsing
# ---------------------------------------------------------------------------

@dataclass
class Header:
    version: int
    sample_format: int
    dsp_enabled: bool
    over_usb: bool
    channels: int
    seq: int
    frame_index: int
    sample_rate: int
    num_frames: int


def parse_packet(packet: bytes):
    """Returns (Header, float32 samples) or None if this is not our packet."""
    if len(packet) < HEADER_SIZE:
        return None
    (magic, version, flags, channels, _r0, seq, frame_index,
     sample_rate, num_frames, _r1) = struct.unpack_from(HEADER_FORMAT, packet, 0)
    if magic != MAGIC:
        return None

    sample_format = flags & 0x03
    body = packet[HEADER_SIZE:]

    if sample_format == FORMAT_S16:
        samples = np.frombuffer(body, dtype="<i2").astype(np.float32) / 32768.0
    elif sample_format == FORMAT_F32:
        samples = np.frombuffer(body, dtype="<f4").astype(np.float32, copy=True)
    else:
        return None  # Opus lands in Phase 6

    header = Header(
        version=version,
        sample_format=sample_format,
        dsp_enabled=bool(flags & FLAG_DSP),
        over_usb=bool(flags & FLAG_USB),
        channels=channels,
        seq=seq,
        frame_index=frame_index,
        sample_rate=sample_rate,
        num_frames=num_frames,
    )
    return header, samples


# ---------------------------------------------------------------------------
# Jitter buffer with fractional-rate reads
# ---------------------------------------------------------------------------

class JitterBuffer:
    """
    Mono float32 ring with a fractional read position.

    The read position advances by `ratio` frames per output frame, and samples are
    reconstructed with Catmull-Rom interpolation. That single mechanism does both
    jobs: it plays the stream out, and it stretches or compresses it by the fraction
    of a percent needed to track the sender's clock.
    """

    def __init__(self, sample_rate: int, capacity_seconds: float = 4.0):
        self.sample_rate = sample_rate
        self.capacity = int(sample_rate * capacity_seconds)
        self.buf = np.zeros(self.capacity, dtype=np.float32)
        self.write_pos = 0          # absolute frame count written
        self.read_pos = 0.0         # absolute fractional frame position
        self.underruns = 0
        self.overflows = 0
        self.consecutive_gaps = 0
        self._last_output = np.zeros(0, dtype=np.float32)

    @property
    def available(self) -> float:
        return self.write_pos - self.read_pos

    def reset_to(self, prefill_frames: int) -> None:
        self.read_pos = float(self.write_pos - prefill_frames)

    def write(self, samples: np.ndarray) -> None:
        n = len(samples)
        if n == 0:
            return
        if n > self.capacity:
            samples = samples[-self.capacity:]
            n = len(samples)

        start = self.write_pos % self.capacity
        first = min(n, self.capacity - start)
        self.buf[start:start + first] = samples[:first]
        if n > first:
            self.buf[:n - first] = samples[first:]
        self.write_pos += n

        # If the reader has fallen more than the ring behind, it is about to read
        # data that has already been overwritten. Jump it forward instead.
        if self.available > self.capacity - self.sample_rate * 0.1:
            self.overflows += 1
            self.read_pos = self.write_pos - self.sample_rate * 0.05

    def _sample_at(self, positions: np.ndarray) -> np.ndarray:
        """Catmull-Rom interpolation at fractional absolute positions."""
        base = np.floor(positions).astype(np.int64)
        frac = (positions - base).astype(np.float32)

        i0 = (base - 1) % self.capacity
        i1 = base % self.capacity
        i2 = (base + 1) % self.capacity
        i3 = (base + 2) % self.capacity

        p0 = self.buf[i0]
        p1 = self.buf[i1]
        p2 = self.buf[i2]
        p3 = self.buf[i3]

        a = -0.5 * p0 + 1.5 * p1 - 1.5 * p2 + 0.5 * p3
        b = p0 - 2.5 * p1 + 2.0 * p2 - 0.5 * p3
        c = -0.5 * p0 + 0.5 * p2
        return ((a * frac + b) * frac + c) * frac + p1

    def read(self, frames: int, ratio: float) -> np.ndarray:
        """
        Reads `frames` output frames, consuming `frames * ratio` input frames.
        Conceals rather than blocking when the buffer has run dry.
        """
        needed = frames * ratio + 2  # +2 for the interpolator's lookahead
        if self.available < needed:
            self.underruns += 1
            self.consecutive_gaps += 1
            return self._conceal(frames)

        self.consecutive_gaps = 0
        positions = self.read_pos + np.arange(frames, dtype=np.float64) * ratio
        out = self._sample_at(positions).astype(np.float32)
        self.read_pos += frames * ratio
        self._last_output = out
        return out

    def _conceal(self, frames: int) -> np.ndarray:
        """
        Packet loss concealment.

        One gap: repeat the tail of the last output under a cosine fade, which keeps
        the waveform continuous. Two or more: fade to silence, because repeating a
        fragment more than once turns into an obvious stutter.
        """
        if len(self._last_output) == 0 or self.consecutive_gaps > 2:
            return np.zeros(frames, dtype=np.float32)

        source = self._last_output
        repeats = int(np.ceil(frames / len(source)))
        tail = np.tile(source, repeats)[:frames]
        fade = np.cos(np.linspace(0, np.pi / 2, frames, dtype=np.float32)) ** 2
        if self.consecutive_gaps > 1:
            fade *= 0.5
        return (tail * fade).astype(np.float32)


# ---------------------------------------------------------------------------
# Drift control
# ---------------------------------------------------------------------------

class DriftController:
    """
    PI controller holding the jitter buffer at its target fill.

    Its output is a resampling ratio a few parts in ten thousand either side of 1.0:
    slow enough to be inaudible, fast enough to absorb a 100 ppm clock difference.

    Three details that a first version gets wrong, and that a real audio clock
    exposes immediately:

    * **Units.** The error is a duration in seconds, so kp is per second and ki per
      second squared, and the integral is accumulated against real elapsed time
      rather than per callback. Gains chosen without that end up either inert or
      railed - a 10 ms error should be corrected over roughly a second, which means
      kp near 0.5, not 0.02.
    * **Anti-windup.** Clamping the integral is not enough; once the output
      saturates, integrating further only makes the recovery longer. Integration
      stops while the output is railed in the direction that would make it worse.
    * **Measurement noise.** Host audio callbacks arrive in bursts, so the raw fill
      level swings by whole packets even on a perfectly healthy stream. The
      controller tracks a smoothed fill so it responds to drift rather than to the
      host's scheduling.
    """

    MAX_DEVIATION = 0.005  # +-0.5%, comfortably beyond any real clock difference

    def __init__(self, sample_rate: int, kp: float = 0.5, ki: float = 0.05,
                 smoothing_seconds: float = 0.4):
        self.sample_rate = sample_rate
        self.kp = kp
        self.ki = ki
        self.smoothing_seconds = smoothing_seconds
        self.integral = 0.0
        self.ratio = 1.0
        self.smoothed_fill = None

    def update(self, fill_frames: float, target_frames: float, dt: float) -> float:
        # Smooth the measurement, not the output: a one-pole over roughly half a
        # second removes callback bursting without hiding a real trend.
        if self.smoothed_fill is None:
            self.smoothed_fill = fill_frames
        else:
            alpha = min(1.0, dt / max(self.smoothing_seconds, 1e-6))
            self.smoothed_fill += alpha * (fill_frames - self.smoothed_fill)

        error = (self.smoothed_fill - target_frames) / self.sample_rate  # seconds

        proportional = self.kp * error
        candidate = proportional + self.ki * (self.integral + error * dt)

        # Conditional integration: only accumulate when doing so does not push an
        # already-saturated output further into its limit.
        saturated_high = candidate > self.MAX_DEVIATION and error > 0
        saturated_low = candidate < -self.MAX_DEVIATION and error < 0
        if not (saturated_high or saturated_low):
            self.integral += error * dt

        adjust = proportional + self.ki * self.integral
        self.ratio = float(np.clip(1.0 + adjust,
                                   1.0 - self.MAX_DEVIATION,
                                   1.0 + self.MAX_DEVIATION))
        return self.ratio

    @property
    def saturated(self) -> bool:
        return abs(self.ratio - 1.0) >= self.MAX_DEVIATION - 1e-9

    def reset(self) -> None:
        self.integral = 0.0
        self.ratio = 1.0
        self.smoothed_fill = None


# ---------------------------------------------------------------------------
# Statistics
# ---------------------------------------------------------------------------

@dataclass
class Stats:
    packets: int = 0
    lost: int = 0
    reordered: int = 0
    bytes_in: int = 0
    jitter_ms: float = 0.0
    lock: threading.Lock = field(default_factory=threading.Lock)


# ---------------------------------------------------------------------------
# Receiver
# ---------------------------------------------------------------------------

class Receiver:
    def __init__(self, args):
        self.args = args
        self.sample_rate = DEFAULT_SAMPLE_RATE
        self.buffer = JitterBuffer(self.sample_rate)
        self.drift = DriftController(self.sample_rate)
        self.stats = Stats()
        self.running = threading.Event()
        self.running.set()

        self.target_ms = float(args.target_ms)
        self.min_ms = float(args.min_ms)
        self.max_ms = float(args.max_ms)

        self.expected_seq = None
        self.stream_started = False
        self.primed = False

        # Learned from the output callback rather than assumed: the host decides how
        # much it asks for at a time, and the buffer has to cover it.
        self.output_block_frames = args.blocksize if args.blocksize > 0 else 480
        self.railed_seconds = 0.0
        self.target_floor_ms = float(args.target_ms)
        self._last_underruns = 0
        self.device_name = None
        self.peer_address = None
        self.peer_is_usb = False
        self.packet_frames = 240

        # RFC 3550 style interarrival jitter, in frames.
        self._jitter = 0.0
        self._last_transit = None

        self.dump = open(args.dump, "wb") if args.dump else None

    # -- network -----------------------------------------------------------

    def receive_loop(self, sock: socket.socket) -> None:
        while self.running.is_set():
            try:
                packet, addr = sock.recvfrom(4096)
            except socket.timeout:
                continue
            except OSError:
                break

            parsed = parse_packet(packet)
            if parsed is None:
                continue
            header, samples = parsed

            if header.channels != 1:
                # Phase 2 is mono only; fold anything else down so it still plays.
                samples = samples.reshape(-1, header.channels).mean(axis=1)

            self._track_sequence(header)
            self._track_jitter(header)

            if not self.stream_started:
                self.sample_rate = header.sample_rate
                self.packet_frames = header.num_frames
                self.stream_started = True
                print(f"stream: {header.sample_rate} Hz, {header.channels} ch, "
                      f"{header.num_frames} frames/packet, dsp={header.dsp_enabled}, "
                      f"link={'usb' if header.over_usb else 'net'}")

            if self.peer_address != addr[0] or self.peer_is_usb != header.over_usb:
                self.peer_address = addr[0]
                self.peer_is_usb = header.over_usb
                print(f"sender: {self.peer_address}"
                      f"{' over USB' if self.peer_is_usb else ' over the network'}")

            self.buffer.write(samples)

            with self.stats.lock:
                self.stats.packets += 1
                self.stats.bytes_in += len(packet)

    def _track_sequence(self, header: Header) -> None:
        if self.expected_seq is None:
            self.expected_seq = (header.seq + 1) & 0xFFFFFFFF
            return
        gap = (header.seq - self.expected_seq) & 0xFFFFFFFF
        with self.stats.lock:
            if gap == 0:
                pass
            elif gap < 0x80000000:
                self.stats.lost += gap
            else:
                self.stats.reordered += 1
        self.expected_seq = (header.seq + 1) & 0xFFFFFFFF

    def _track_jitter(self, header: Header) -> None:
        """Interarrival jitter against the sender's own frame counter."""
        arrival_frames = time.monotonic() * self.sample_rate
        transit = arrival_frames - header.frame_index
        if self._last_transit is not None:
            d = abs(transit - self._last_transit)
            self._jitter += (d - self._jitter) / 16.0
            with self.stats.lock:
                self.stats.jitter_ms = self._jitter * 1000.0 / self.sample_rate
        self._last_transit = transit

    def discovery_loop(self) -> None:
        """
        Answers "where are you?" broadcasts from the phone.

        The reply carries only the audio port; the phone learns our address from the
        packet's source, which is what makes this work unchanged across Wi-Fi and a
        USB tether whose subnet we cannot predict.
        """
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            sock.bind(("0.0.0.0", DISCOVERY_PORT))
        except OSError as exc:
            print(f"discovery responder disabled: {exc}")
            return
        sock.settimeout(0.5)
        print(f"answering discovery probes on UDP {DISCOVERY_PORT}")

        reply = REPLY_MAGIC + bytes([DISCOVERY_VERSION]) + struct.pack("<H", self.args.port)
        while self.running.is_set():
            try:
                packet, addr = sock.recvfrom(64)
            except socket.timeout:
                continue
            except OSError:
                break
            if len(packet) >= 9 and packet[:8] == PROBE_MAGIC:
                try:
                    sock.sendto(reply, addr)
                    print(f"discovery probe from {addr[0]}; replied")
                except OSError:
                    pass
        sock.close()

    # -- audio -------------------------------------------------------------

    def target_frames(self) -> float:
        """
        Adaptive target fill, sized from measurement rather than guessed.

        Three contributions, all of which have to be covered or the buffer runs dry:

        * one packet, because audio arrives in whole packets;
        * three times the measured network jitter;
        * two output blocks, because the host asks for audio in its own block size
          and on its own schedule, not ours. Leaving this out is how a target ends
          up mathematically unreachable - the controller then drains flat out
          forever, sitting on its limit and never converging.
        """
        jitter_ms = self.stats.jitter_ms
        packet_ms = self.packet_frames * 1000.0 / self.sample_rate
        block_ms = self.output_block_frames * 1000.0 / self.sample_rate

        wanted = max(self.target_floor_ms,
                     packet_ms + 2.0 * block_ms + 3.0 * jitter_ms)
        wanted = min(max(wanted, self.min_ms), self.max_ms)
        return wanted * self.sample_rate / 1000.0

    def state_key(self) -> str:
        """Learned targets are per device *and* per link: a cable needs far less."""
        link = "usb" if self.peer_is_usb else "net"
        return f"{self.device_name} [{link}]"

    def note_underruns(self) -> None:
        """
        Raise the jitter target when the buffer actually runs dry.

        A device's declared latency is a floor, not a promise: WASAPI in shared mode
        schedules callbacks with more slack than it admits to, and a target sized
        purely from the declared figure starves occasionally. Rather than pick a
        conservative constant that is wrong on every other device, watch for the
        failure and give the buffer one more packet plus one more block each time it
        happens. It converges within the first minute and then stops moving.
        """
        if not self.primed:
            return
        current = self.buffer.underruns
        if current > self._last_underruns and self.target_floor_ms < self.max_ms:
            packet_ms = self.packet_frames * 1000.0 / self.sample_rate
            block_ms = self.output_block_frames * 1000.0 / self.sample_rate
            self.target_floor_ms = min(self.max_ms,
                                       self.target_floor_ms + packet_ms + block_ms)
            print(f"underrun; raising jitter target floor to {self.target_floor_ms:.0f} ms")
            if self.device_name:
                save_learned_target(self.state_key(), self.target_floor_ms)
        self._last_underruns = current

    def note_saturation(self, dt: float) -> None:
        """
        A controller stuck on its limit is reporting that the target cannot be
        reached, not that the clocks differ by half a percent. Believe it and raise
        the floor rather than leaving the stream to underrun indefinitely.
        """
        if self.drift.saturated:
            self.railed_seconds += dt
            if self.railed_seconds > 3.0 and self.target_floor_ms < self.max_ms:
                self.target_floor_ms = min(self.max_ms, self.target_floor_ms * 1.5)
                self.drift.reset()
                self.railed_seconds = 0.0
                print(f"drift controller railed for 3 s; raising target floor to "
                      f"{self.target_floor_ms:.0f} ms")
        else:
            self.railed_seconds = 0.0

    def audio_callback(self, outdata, frames, _time_info, status) -> None:
        if status:
            # Underflow/overflow reported by PortAudio itself.
            pass

        self.output_block_frames = frames

        # Prime on the first callback *after* audio starts arriving, not merely on
        # the first callback. A receiver started before the phone would otherwise
        # prime against an empty buffer and then count an underrun on every callback
        # while it waits - thousands of them, which are not glitches in a stream that
        # has not begun, and which would drive the target-raising watchdog into
        # inflating the buffer for no reason.
        if not self.primed:
            if not self.stream_started:
                outdata[:, 0] = 0.0
                return
            self.primed = True
            self.buffer.reset_to(int(self.target_frames()))
            self.drift.reset()
            self.buffer.underruns = 0
            self._last_underruns = 0

        target = self.target_frames()
        dt = frames / self.sample_rate
        ratio = 1.0 if self.args.no_drift else self.drift.update(
            self.buffer.available, target, dt)
        self.note_saturation(dt)
        block = self.buffer.read(frames, ratio)
        outdata[:, 0] = block
        if self.dump:
            self.dump.write(block.tobytes())

    def run_silent(self) -> None:
        """
        Exercises the full receive path with no output device.

        Same jitter buffer, drift controller and concealment as the real callback;
        only the clock differs. Useful for verifying the receiver on a machine with
        no virtual cable installed, and for CI.
        """
        block = self.args.blocksize
        print(f"silent mode: pulling {block}-frame blocks, no audio device")
        next_deadline = time.perf_counter()
        interval = block / self.sample_rate

        try:
            while True:
                if not self.primed:
                    if not self.stream_started:
                        time.sleep(interval)
                        continue
                    self.primed = True
                    self.buffer.reset_to(int(self.target_frames()))
                    self.drift.reset()
                    self.buffer.underruns = 0
                    self._last_underruns = 0

                target = self.target_frames()
                ratio = 1.0 if self.args.no_drift else self.drift.update(
                    self.buffer.available, target, interval)
                self.note_saturation(interval)
                out = self.buffer.read(block, ratio)
                if self.dump:
                    self.dump.write(out.tobytes())

                next_deadline += interval
                sleep_for = next_deadline - time.perf_counter()
                if sleep_for > 0:
                    time.sleep(sleep_for)
                else:
                    # Fell behind; resync rather than spiral.
                    next_deadline = time.perf_counter()
        except KeyboardInterrupt:
            print("stopping")

    # -- reporting ---------------------------------------------------------

    def report_loop(self) -> None:
        last = time.monotonic()
        last_packets = 0
        last_bytes = 0
        self._last_underruns = self.buffer.underruns
        while self.running.is_set():
            time.sleep(1.0)
            now = time.monotonic()
            elapsed = now - last
            last = now
            with self.stats.lock:
                packets = self.stats.packets
                bytes_in = self.stats.bytes_in
                lost = self.stats.lost
                reordered = self.stats.reordered
                jitter = self.stats.jitter_ms
            dp = packets - last_packets
            db = bytes_in - last_bytes
            last_packets, last_bytes = packets, bytes_in
            self.note_underruns()

            fill_ms = self.buffer.available * 1000.0 / self.sample_rate
            print(f"{dp / elapsed:6.1f} pkt/s  {db * 8 / elapsed / 1000:7.1f} kbit/s  "
                  f"buffer {fill_ms:5.1f} ms (target {self.target_frames() * 1000 / self.sample_rate:4.1f}) "
                  f"jitter {jitter:4.1f} ms  ratio {self.drift.ratio:.5f}"
                  f"{' RAILED' if self.drift.saturated else '      '}  "
                  f"{'USB  ' if self.peer_is_usb else ''}"
                  f"lost {lost}  reorder {reordered}  "
                  f"under {self.buffer.underruns}  over {self.buffer.overflows}")

    # -- lifecycle ---------------------------------------------------------

    def run(self) -> None:
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 1 << 21)
        sock.bind((self.args.host, self.args.port))
        sock.settimeout(0.5)
        print(f"listening on {self.args.host}:{self.args.port}")

        zeroconf, service = (None, None) if self.args.no_advertise else advertise(self.args.port)

        receiver_thread = threading.Thread(target=self.receive_loop, args=(sock,), daemon=True)
        receiver_thread.start()
        reporter = threading.Thread(target=self.report_loop, daemon=True)
        reporter.start()
        if not self.args.no_discovery:
            threading.Thread(target=self.discovery_loop, daemon=True).start()

        if self.args.no_audio:
            self.run_silent()
            self.running.clear()
            if zeroconf is not None:
                zeroconf.unregister_service(service)
                zeroconf.close()
            sock.close()
            if self.dump:
                self.dump.close()
            return

        device = resolve_device(self.args.device)
        info = sd.query_devices(device if device is not None else sd.default.device[1])
        print(f"output device: {info['name']}")

        # Seed the jitter target from what the host says it needs, rather than
        # starting optimistically and discovering the truth through a minute of
        # underruns. WASAPI in shared mode delivers on the device's own period no
        # matter what block size we ask for, so that period, not our request, is
        # what the buffer has to cover.
        device_rate = int(info.get("default_samplerate", self.sample_rate))
        if device_rate != self.sample_rate:
            print(f"WARNING: device runs at {device_rate} Hz but the stream is "
                  f"{self.sample_rate} Hz. Set the device to {self.sample_rate} Hz "
                  f"(for VB-CABLE, in its control panel) - otherwise Windows resamples "
                  f"every sample and the drift controller is fighting it.")

        host_latency_ms = float(info.get("default_low_output_latency", 0.01)) * 1000.0
        packet_ms = self.packet_frames * 1000.0 / self.sample_rate
        seeded = packet_ms + 2.0 * host_latency_ms + 10.0
        if seeded > self.target_floor_ms:
            self.target_floor_ms = min(self.max_ms, seeded)

        # A device's declared latency is a floor, not a promise; what it actually
        # needed last time is better evidence than what it claims.
        self.device_name = str(info["name"])
        learned = load_learned_target(self.state_key())
        if learned and learned > self.target_floor_ms:
            self.target_floor_ms = min(self.max_ms, float(learned))
            print(f"host reports {host_latency_ms:.1f} ms output latency; "
                  f"using {self.target_floor_ms:.0f} ms learned from a previous run")
        else:
            print(f"host reports {host_latency_ms:.1f} ms output latency; "
                  f"jitter target floor {self.target_floor_ms:.0f} ms")

        # Everything the callback touches is allocated by now. Freezing the existing
        # objects keeps them out of every future GC pass, which is what stops the
        # collector from introducing periodic hitches in the callback.
        gc.freeze()

        blocksize = self.args.blocksize

        # Exclusive mode hands the endpoint over to us alone, which removes the
        # shared-mode mixer and the scheduling slack that comes with it. The cost is
        # that nothing else can use the device while we hold it.
        extra_settings = None
        if self.args.exclusive:
            try:
                extra_settings = sd.WasapiSettings(exclusive=True)
                print("requesting WASAPI exclusive mode")
            except Exception as exc:  # not a WASAPI device, or unsupported
                print(f"exclusive mode unavailable: {exc}")

        with sd.OutputStream(
            samplerate=self.sample_rate,
            channels=1,
            dtype="float32",
            blocksize=blocksize,
            device=device,
            latency="low",
            extra_settings=extra_settings,
            callback=self.audio_callback,
        ):
            print(f"playing (blocksize {blocksize} frames). Ctrl+C to stop.")
            try:
                while True:
                    time.sleep(0.5)
            except KeyboardInterrupt:
                print("\nstopping")

        self.running.clear()
        if zeroconf is not None:
            zeroconf.unregister_service(service)
            zeroconf.close()
        sock.close()
        if self.dump:
            self.dump.close()


def advertise(port: int):
    """
    Publishes _mobimic._udp so the phone can find this PC without anyone typing an
    IP address. Optional: without zeroconf installed the receiver still works, the
    phone just needs the address entered by hand.
    """
    if Zeroconf is None:
        return None, None
    import uuid

    hostname = socket.gethostname()
    try:
        address = socket.inet_aton(socket.gethostbyname(hostname))
    except OSError:
        return None, None

    info = ServiceInfo(
        "_mobimic._udp.local.",
        f"{hostname}._mobimic._udp.local.",
        addresses=[address],
        port=port,
        properties={"version": "1", "format": "s16"},
        server=f"mobimic-{uuid.getnode():x}.local.",
    )
    zeroconf = Zeroconf()
    zeroconf.register_service(info)
    print(f"advertising _mobimic._udp on {socket.inet_ntoa(address)}:{port}")
    return zeroconf, info


def resolve_device(name: str | None):
    """
    Finds an output device by name, preferring the host API that is actually fit for
    this job.

    Windows exposes the same endpoint through several host APIs, and they are not
    equivalent. VB-CABLE, for instance, appears under MME at 44.1 kHz with 90 ms of
    declared latency and under WASAPI at 48 kHz with 2 ms. Taking the first name
    match picks MME, which silently costs a resampler and 88 ms - so rank the
    candidates instead of taking whichever comes first.
    """
    if not name:
        return None

    devices = sd.query_devices()
    hostapis = sd.query_hostapis()
    lowered = name.lower()

    # Lower is better.
    preference = {"Windows WASAPI": 0, "Windows WDM-KS": 1, "Windows DirectSound": 2, "MME": 3}

    candidates = []
    for index, device in enumerate(devices):
        if device["max_output_channels"] <= 0:
            continue
        if lowered not in device["name"].lower():
            continue
        api = hostapis[device["hostapi"]]["name"]
        candidates.append((
            preference.get(api, 9),
            device["default_low_output_latency"],
            index,
            api,
            device,
        ))

    if not candidates:
        raise SystemExit(f"no output device matching {name!r}. Try --list-devices.")

    candidates.sort(key=lambda c: (c[0], c[1]))
    _, _, index, api, device = candidates[0]
    if len(candidates) > 1:
        print(f"{len(candidates)} devices match {name!r}; using the {api} one "
              f"({device['default_samplerate']:.0f} Hz, "
              f"{device['default_low_output_latency'] * 1000:.1f} ms)")
    return index


def main() -> None:
    parser = argparse.ArgumentParser(description="mobiMic receiver")
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT)
    parser.add_argument("--device", default="CABLE Input",
                        help="output device name substring; empty string for the default device")
    parser.add_argument("--list-devices", action="store_true")
    parser.add_argument("--blocksize", type=int, default=240,
                        help="PortAudio callback size in frames (240 = 5 ms at 48 kHz)")
    parser.add_argument("--target-ms", type=float, default=20.0,
                        help="baseline jitter buffer target")
    parser.add_argument("--min-ms", type=float, default=5.0)
    parser.add_argument("--max-ms", type=float, default=60.0)
    parser.add_argument("--no-drift", action="store_true",
                        help="disable clock drift compensation (for A/B testing it)")
    parser.add_argument("--exclusive", action="store_true",
                        help="open the output in WASAPI exclusive mode (lower latency, "
                             "but takes sole ownership of the device)")
    parser.add_argument("--no-audio", action="store_true",
                        help="run the receive path without opening an output device")
    parser.add_argument("--no-discovery", action="store_true",
                        help="do not answer discovery probes from the phone")
    parser.add_argument("--no-advertise", action="store_true",
                        help="do not publish this receiver over mDNS")
    parser.add_argument("--dump", default=None,
                        help="write the played audio to this file as raw float32")
    args = parser.parse_args()

    if sd is None and not args.no_audio:
        raise SystemExit("sounddevice is not installed. pip install -r pc/requirements.txt")

    if args.list_devices:
        print(sd.query_devices())
        return

    Receiver(args).run()


if __name__ == "__main__":
    sys.exit(main())
