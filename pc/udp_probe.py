"""
Phase 1.5 - dumb UDP probe.

No audio output, no jitter buffer, no clock handling. It exists so the Phase 2
Android sender has something to aim at, and so packet rate and loss can be seen
before any of the real receiver machinery is written.

Usage:
    python udp_probe.py --port 47001 --dump capture.raw

The dump is headerless PCM. Import into Audacity as "raw data", matching whatever
format the sender is using (48000 Hz, mono, signed 16-bit little-endian by default).
"""

from __future__ import annotations

import argparse
import socket
import struct
import time

HEADER_FORMAT = "<4sBBBBIQIHH"
HEADER_SIZE = struct.calcsize(HEADER_FORMAT)
MAGIC = b"MMIC"

FORMAT_NAMES = {0: "pcm_s16le", 1: "pcm_f32le", 2: "opus"}


def parse_header(packet: bytes):
    """Returns the decoded header, or None if this is not a mobiMic packet."""
    if len(packet) < HEADER_SIZE:
        return None
    (magic, version, flags, channels, _res0, seq, frame_index,
     sample_rate, num_frames, _res1) = struct.unpack_from(HEADER_FORMAT, packet, 0)
    if magic != MAGIC:
        return None
    return {
        "version": version,
        "format": FORMAT_NAMES.get(flags & 0x03, "unknown"),
        "dsp": bool(flags & 0x04),
        "channels": channels,
        "seq": seq,
        "frame_index": frame_index,
        "sample_rate": sample_rate,
        "num_frames": num_frames,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="mobiMic UDP probe")
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=47001)
    parser.add_argument("--dump", default=None, help="write payloads to this file")
    args = parser.parse_args()

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 1 << 20)
    sock.bind((args.host, args.port))
    sock.settimeout(1.0)

    dump = open(args.dump, "wb") if args.dump else None
    print(f"listening on {args.host}:{args.port}"
          + (f", dumping to {args.dump}" if dump else ""))

    packets = 0
    payload_bytes = 0
    lost = 0
    unknown = 0
    expected_seq = None
    last_report = time.monotonic()
    first_header = None

    try:
        while True:
            try:
                packet, _addr = sock.recvfrom(2048)
            except socket.timeout:
                packet = None

            if packet:
                header = parse_header(packet)
                if header is None:
                    unknown += 1
                else:
                    if first_header is None:
                        first_header = header
                        print(f"stream: {header['sample_rate']} Hz, "
                              f"{header['channels']} ch, {header['format']}, "
                              f"{header['num_frames']} frames/packet, "
                              f"dsp={header['dsp']}")
                    if expected_seq is not None and header["seq"] != expected_seq:
                        # Negative gaps are reordering, positive gaps are loss.
                        gap = (header["seq"] - expected_seq) & 0xFFFFFFFF
                        if gap < 0x80000000:
                            lost += gap
                    expected_seq = (header["seq"] + 1) & 0xFFFFFFFF

                    body = packet[HEADER_SIZE:]
                    payload_bytes += len(body)
                    packets += 1
                    if dump:
                        dump.write(body)

            now = time.monotonic()
            if now - last_report >= 1.0:
                elapsed = now - last_report
                print(f"{packets / elapsed:7.1f} pkt/s  "
                      f"{payload_bytes * 8 / elapsed / 1000:8.1f} kbit/s  "
                      f"lost={lost}  unknown={unknown}")
                packets = 0
                payload_bytes = 0
                last_report = now
    except KeyboardInterrupt:
        print("\nstopped")
    finally:
        if dump:
            dump.close()
        sock.close()


if __name__ == "__main__":
    main()
