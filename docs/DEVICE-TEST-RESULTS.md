# Device test results

Measured on a realme RMX3842 (Android 16, API 36, arm64-v8a), streaming over Wi-Fi to a
Windows PC on the same subnet. Recorded here because these numbers are the baseline every
later change gets compared against.

## Capture path (Phase 1)

| Check | Result |
|---|---|
| `PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED` | supported |
| Input preset actually obtained | `Unprocessed` |
| AGC | not present on this device |
| Noise suppressor | attached and **disabled** |
| Echo canceller | attached and **disabled** |
| Audio API | AAudio |
| Sample rate | 48 000 Hz |
| Device format granted | I16 stereo, converted to mono float in the callback |
| Frames per burst | 96 (2 ms) after the format fix; 960 (20 ms) before it |
| Buffer capacity | 4096 frames |
| XRuns | 0 |
| Frames dropped | 0 |

Raw WAV analysis, 13.6 s of a quiet room:

- 32-bit float, 48 kHz, mono, no NaNs, no clipped samples, DC offset −2.7e−5.
- Noise floor −63 to −65 dBFS, 10.2 dB total spread across 100 ms blocks.
- **0 of 136 blocks fell below −100 dBFS**, so nothing is gating the signal to digital
  black, and the floor does not breathe. This is the Phase 1 exit criterion, met.

## Known limitation: the buffer will not shrink

`bufferSizeFrames == bufferCapacityFrames == 2880`, so the adaptive tuner has no room to
work and the device is not giving a genuine low-latency input path despite
`PerformanceMode::LowLatency` and `SharingMode::Exclusive`. 60 ms of buffer is six times
the roadmap's 5–10 ms estimate and is now the single largest latency contributor on this
handset — larger than the network, the jitter buffer and the DSP combined.

Worth investigating before any further DSP optimisation: whether this device exposes an
MMAP/EXCLUSIVE input path at all, and whether a smaller requested buffer or a different
input preset changes what AAudio hands back.

## DSP chain (Phase 3)

Broadcast preset applied, processed capture compared against the raw one. Average spectrum
per band, processed minus raw:

| Band | Delta |
|---|---|
| 30–60 Hz | −14.7 dB |
| 60–100 Hz | +4.1 dB |
| 100–200 Hz | +9.5 dB |
| 200–500 Hz | +7.2 dB |
| 0.5–1.5 kHz | +9.8 dB |
| 1.5–3 kHz | +11.2 dB |
| 3–6 kHz | +12.6 dB |
| 6–12 kHz | +11.5 dB |
| 12–20 kHz | +11.6 dB |

The broadband lift is the preset's +6 dB input gain plus auto makeup. Against that
baseline the 85 Hz high-pass is pulling 30–60 Hz down by roughly 26 dB, and 3–6 kHz is the
most-boosted band, which is the presence lift the preset asks for. Nothing exceeded the
−1 dBFS limiter ceiling and no NaNs reached the output.

CPU cost, as worst-case callback duration against its deadline:

| Configuration | Callback load |
|---|---|
| Bypass (capture only) | 3.6 % |
| Full chain, Broadcast preset | 18.1 % |

That leaves substantial headroom for the Phase 4 suppressor.

## Transport (Phase 2)

Over Wi-Fi, phone 192.168.31.173 to PC 192.168.31.84:

- 200.0 packets/s (48000 / 240, exactly as designed), 812 kbit/s including headers.
- 0 packets lost, 2 reordered over several minutes — the reordering was absorbed.
- Interarrival jitter 7.1–7.3 ms; the adaptive jitter target settled at 26.3–26.9 ms,
  which is 3x jitter plus one packet, as specified.
- Wire header verified live: `magic=MMIC version=1 format=0 dsp=True channels=1
  frames=240 packet_bytes=508`.

The drift ratio sat at roughly 1.003–1.005 rather than the few parts per million a real
clock difference produces. That is an artefact of the `--no-audio` test harness, which
paces itself with `time.sleep` on Windows rather than a real audio clock; it is not a
measurement of the drift controller against a sound card. Re-measure once a virtual cable
is installed and the PortAudio callback is driving the loop.

## Not yet tested

- Playback into a virtual audio cable. VB-CABLE is not installed on this PC, so the
  PortAudio output path and the drift controller under a real audio clock remain unproven.
- Long-run stability (the roadmap asks for 30 minutes of continuous streaming).
- Behaviour with the screen off, on battery, and across a Wi-Fi to USB switch.


## Headroom after the format fix

Worst-case callback duration as a fraction of its 2 ms deadline, measured on the fast path:

| Configuration | Callback load | XRuns | Frames dropped |
|---|---|---|---|
| Capture only (chain bypassed) | 3.6 % | 0 | 0 |
| Broadcast preset, no suppression | 12.7 % | 0 | 0 |
| Meeting preset, spectral suppression on | 37.2 % | 0 | 0 |

Still under the 50 % target with the suppressor running inside a 2 ms callback, so the
off-thread execution mode the roadmap holds in reserve is not needed for this suppressor
on this device.

Reported algorithmic delay is now the whole chain rather than just the limiter:
96 frames (2 ms) of limiter lookahead alone, 352 frames (7.3 ms) with the suppressor
enabled, since its STFT contributes 256 frames.


## Receiver against a real audio clock

VB-CABLE is not installed on this PC, so the receiver was run into
`Realtek Digital Output` over WASAPI instead. Nothing is connected to that port, so the
test is silent, but it is a real hardware audio clock - which is the whole point, since
the drift controller cannot be evaluated against anything else.

The `--no-audio` harness had hidden three bugs. A wall-clock pacer is not an audio clock,
and every one of these appeared within seconds of using a real one.

### 1. The PI controller was railed, not tracking

The ratio sat pinned at its +0.5 % limit while the buffer swung between 4 ms and 39 ms
around a 20 ms target, and underruns accumulated at roughly one every two seconds.

Three separate mistakes, all in the same controller:

- **Units.** The error is a duration in seconds, so kp is per second. `kp = 0.02` means a
  10 ms error asks for a 0.02 % correction, which is nothing; the proportional term did no
  work at all and the integral did everything. Correcting a 10 ms error over about a
  second needs kp near 0.5.
- **Windup.** Clamping the integral is not anti-windup. Once the output saturates,
  continuing to integrate only lengthens the recovery. Integration now stops while the
  output is on its limit in the direction that would make things worse.
- **Measurement noise.** Host audio callbacks arrive in bursts, so raw buffer fill swings
  by whole packets on a perfectly healthy stream. The controller now tracks a smoothed
  fill, so it responds to drift rather than to the host's scheduling.

### 2. The target ignored the output block size

The jitter target was sized from network jitter and packet size only. But the host asks
for audio in its own block, on its own schedule, and the buffer has to cover that too.
With `--blocksize 0` the host period exceeded the entire 20 ms target, making it
arithmetically unreachable: the controller drained at maximum rate forever and never
converged.

The target is now `packet + 2 x output block + 3 x jitter`, and the floor is seeded from
the host's own `default_low_output_latency` rather than discovered through a minute of
underruns. A saturation watchdog remains as a backstop: a controller stuck on its limit
for three seconds is reporting that the target is unreachable, so the floor is raised and
the controller reset.

### 3. Priming happened at the wrong moment

The buffer was primed when the first packet arrived, but playback starts later, so
everything received in between became backlog the controller had to drain at its limit -
13 seconds of railed output at 0.5 % off pitch. Priming now happens on the first output
callback, when the host actually starts consuming.

### Result

| | Before | After |
|---|---|---|
| Underruns | 69 -> 81 in 30 s | **0 in 100 s** |
| Drift ratio | pinned at 1.00500 | converges to 1.0000 ± 0.0003 |
| Seconds spent railed | continuous | **0** |
| Buffer vs target | 4-39 ms around 20 ms | 50-64 ms around 60 ms |
| Packets lost / reordered | 0 / 0 | 0 / 0 |

Network jitter also dropped from 7.1 ms to about 1.5 ms, which is the phone-side burst fix
showing up at the far end: the sender now hands over a packet every 2 ms instead of
batching 20 ms of audio at a time.

### What the SPDIF test did not prove

The 60 ms target there is that device's number, not a universal one - Realtek's SPDIF
output declares a generous 90 ms. VB-CABLE was covered separately, below.

---

## VB-CABLE

Installed and tested end to end. The full chain works: phone capsule to Oboe to DSP to
UDP to jitter buffer to VB-CABLE to a Windows recording device.

### Windows really does see it

Proof taken the way any other application would: `CABLE Output` opened as an ordinary
WASAPI recording device while the receiver played into `CABLE Input`.

```
recording from: CABLE Output (VB-Audio Virtual Cable) (WASAPI, 48000 Hz)
frames 480000  non-zero 99.5%
peak -46.7 dBFS  rms -61.0 dBFS
100 ms blocks: min -63.8  max -57.6 dBFS
silent blocks (< -100 dBFS): 0 of 100
```

### Host API selection was silently wrong

VB-CABLE appears under four host APIs, and they are not equivalent:

| Host API | Rate | Declared output latency |
|---|---|---|
| MME | 44 100 Hz | 90 ms |
| DirectSound | 44 100 Hz | 120 ms |
| **WASAPI** | **48 000 Hz** | **2 ms** |

`resolve_device()` returned the first name match, which is MME - 44.1 kHz and 90 ms. That
would have added a resampler nobody asked for and 88 ms of latency, with nothing to
indicate it. Device selection now ranks candidates by host API and latency, and prints
which one it chose. A sample-rate mismatch also prints a warning rather than being
silently resampled by Windows.

### The wire format was not what it claimed

The first VB-CABLE run pushed 1.58 Mbit/s instead of 812 kbit/s. Decoding a live packet
gave `flags=0x05`: 988 bytes, `pcm_f32le`. The phone's stored setting had ended up on
float32, so the stream was carrying twice the data for no benefit. A `SET_WIRE_FORMAT`
adb action now sets it without going near the touchscreen, and int16 is confirmed back at
508-byte packets and 812 kbit/s.

### Declared latency is a floor, not a promise

VB-CABLE declares 2 ms, which seeds a 20 ms jitter target. At that target the buffer
starved roughly once every 20 seconds. WASAPI in shared mode schedules callbacks with far
more slack than it admits to.

Rather than hardcode a conservative constant that would be wrong on the next machine,
the receiver now watches for the failure: each underrun raises the target by one packet
plus one output block. It converged to 50 ms and then ran **132 seconds with no further
underruns**, and a separate run at `--target-ms 50` from the start managed **59 seconds
with zero underruns of any kind**.

The learned figure is persisted per device in `pc/receiver_state.json`, so the next launch
starts where the last one converged instead of relearning through the same minute of
glitches.

### Exclusive mode is worse here, and that is worth knowing

WASAPI exclusive mode was the obvious next lever, being the receiver-side equivalent of
the phone's burst fix. Measured, it was worse: the target escalated to the 60 ms cap with
twice the underruns. VB-CABLE is a virtual device, so there is no tighter hardware path to
win, and exclusive mode only adds buffering. Shared mode stays the default. The
`--exclusive` flag remains for real hardware endpoints, where the answer may differ.

### Where the latency actually goes

| Stage | Measured |
|---|---|
| Phone capture burst | 2.0 ms |
| DSP (limiter lookahead) | 2.0 ms, or 7.3 ms with suppression |
| Packetisation | 5.0 ms |
| Wi-Fi jitter | 0.9-2.7 ms |
| **Jitter buffer** | **50-60 ms** |
| VB-CABLE plus consuming app | not measured |

The jitter buffer is now the dominant term by an order of magnitude, which is where the
next optimisation belongs - not in the DSP, and no longer on the phone.

### Honest caveat on stability

Long clean stretches with occasional bursts. One 90 s run held 75 seconds with zero
underruns and then took 7 in a single second, with the controller briefly railing before
recovering. `lost 0 reorder 0` throughout, so the packets did arrive - the burst is
host-side scheduling, not the network. The roadmap's 30-minute continuous-streaming
criterion has not been run yet, and that is the test that would characterise this properly.

### Note on your Windows defaults

Installing VB-CABLE made it the default Windows playback **and** recording device:

```
default input : CABLE Output (VB-Audio Virtual Cable)
default output: CABLE Input (VB-Audio Virtual Cable)
```

That means all system audio is currently being routed into the cable and will not reach
your speakers. That is VB-CABLE's installer doing it, not this project, and it is worth
setting the defaults back in Windows sound settings - mobiMic addresses the cable
explicitly by name and does not need to be the default.


## USB tethering

Added and measured against Wi-Fi, same procedure, same duration, learned targets cleared
first so neither link inherited the other's figure.

### What the phone reports

```
links: rndis0=10.194.134.169(USB), wlan0=192.168.31.173(WIFI)
discovery found 2: 10.194.134.175:47001 via rndis0 (USB), 192.168.31.84:47001 via wlan0
Sending from local address 10.194.134.169
streaming to 10.194.134.175:47001 via rndis0 (usb=true, local=10.194.134.169)
```

Discovery answers on both links simultaneously and the cable is preferred, which is the
point: a tether hands out a new subnet every time the cable goes in, so a stored address
is wrong exactly when you wanted the cable.

### Head to head

| | USB tether | Wi-Fi |
|---|---|---|
| Learned jitter target | **30 ms** | 40 ms |
| Underruns in 90 s | **1** | 4 |
| Packets lost / reordered | 0 / 0 | 0 / 0 |
| Drift ratio | 1.0000 ± 0.0003 | 1.0000 ± 0.0003 |
| Measured jitter | 1.4-2.6 ms | 1.8-3.1 ms |

USB is better, but by 10 ms rather than the large margin the roadmap assumed. That is
worth stating plainly: **the bottleneck is no longer the network.** WASAPI shared-mode
callback scheduling needs roughly 30 ms of buffer whatever is feeding it, so the cable can
only remove the part of the budget the network was responsible for, which had already
shrunk to a few ms once the phone's burst dropped from 20 ms to 2 ms.

End to end, the phone-plus-buffer figure is about 39 ms over USB and 49 ms over Wi-Fi,
excluding whatever VB-CABLE and the consuming application add.

### Two bugs the USB work exposed

**The tether subnet is not what Android documents.** The receiver originally decided
"is this USB?" by checking for `192.168.42.x`. This phone tethers on `10.194.134.x`, so
the check was simply wrong. Guessing was replaced with fact: bit 3 of the packet header
flags now carries the link type, asserted by the sender, which knows.

**Underruns were counted before the stream existed.** A receiver started before the phone
counted one underrun per callback while waiting - 1640 of them in one run - and those
bogus counts drove the target-raising watchdog into inflating the buffer to 60 ms for no
reason. Priming now waits for the first packet, and the counters reset at that moment.

That second bug also means the earlier Wi-Fi figures in this document, of 50-60 ms, were
partly this artefact rather than a genuine requirement. The 40 ms above is the corrected
number.

### Interface binding matters

With both links up, the kernel routes by routing table, not by intent: without binding the
send socket to the chosen interface's own address, audio nominally "on USB" would leave
over Wi-Fi with nothing to indicate it. The sender now binds explicitly, and the log line
above shows which local address it used.

Choosing **USB** rather than **Auto** makes the app refuse to fall back to Wi-Fi, and it
was seen doing so correctly when no receiver was listening on the cable:

```
No receiver found over USB. Is USB tethering on, and the receiver running?
```
