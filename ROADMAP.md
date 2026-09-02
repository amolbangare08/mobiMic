# mobiMic — Implementation Roadmap

Turning an Android phone into a broadcast-quality, low-latency USB/Wi-Fi microphone for Windows.

This document is the build plan. Each phase ends in something runnable and measurable, so a later
phase never has to debug an earlier one's assumptions.

---

## 0. Baseline decisions and constraints

### 0.1 Fixed decisions (made once, everything else follows)

| Decision | Choice | Rationale |
|---|---|---|
| Internal sample rate | 48 000 Hz | Native rate of nearly all Android hardware and of WASAPI/VB-Cable. Any other rate forces a resampler into the hot path. |
| Internal sample format | 32-bit float, mono | Headroom for DSP without clipping intermediates; mono halves bandwidth and matches a single mic capsule. |
| Wire format | `int16` PCM (default), `float32` (debug), Opus (optional, Phase 6) | 48 kHz mono `int16` is 768 kbit/s — trivial on a LAN, and int16 keeps the Python receiver's conversion cost near zero. |
| Transport | UDP unicast, phone to PC | No retransmission stalls. Loss is concealed, never waited for. |
| Capture API | Oboe (AAudio backend on API 26+) | Callback-driven low-latency path; handles device quirks and stream disconnects. |
| DSP language | C++17, inside the Oboe callback | No JNI, no GC, no allocation on the audio thread. |
| Receiver | Python 3.11+, `sounddevice` (PortAudio/WASAPI) into VB-CABLE | Fast to iterate; a PortAudio callback with preallocated NumPy buffers is fast enough for a mono 48 kHz stream. |
| Control plane | Separate small TCP or UDP channel (Phase 6) | Keeps the audio datagram fixed-size and header-light. |

### 0.2 minSdk

Raise `minSdk` from 24 to **26**. AAudio does not exist below 26; Oboe would silently fall back to
OpenSL ES and the low-latency and `Unprocessed` guarantees disappear. If foreground-service
microphone typing (required by `targetSdk 34+`) proves awkward on 26–28, raise to 29.

### 0.3 Latency budget (realistic targets, mouth to Windows app)

| Stage | Typical | Notes |
|---|---|---|
| Oboe input burst | 2–10 ms | Device-dependent **and format-dependent** — see 1.2. Measured 2 ms (96 frames) on a realme RMX3842, but only in 16-bit; float gave 20 ms. |
| DSP block (EQ/comp/gate) | 0 ms | Runs in-place inside the same callback. |
| Limiter lookahead | 1.5–3 ms | Pure algorithmic delay. |
| AI noise suppression | 10 ms (RNNoise) to 32 ms (DTLN) | Optional; the single largest tunable cost. |
| Packetization | 5 ms | 240 frames per datagram. |
| Wi-Fi (5 GHz, good AP) | 2–15 ms | Long tail; occasional 50 ms+ spikes are normal. |
| Jitter buffer | 10–30 ms adaptive | Sized from measured jitter, not guessed. |
| WASAPI shared + VB-CABLE | 10–20 ms | VB-CABLE's own internal latency setting matters. |
| **Total** | **~35–70 ms** without NS, **~50–90 ms** with | |

Be honest about this up front: sub-20 ms end-to-end over Wi-Fi is not achievable. If that is needed,
the answer is **USB tethering** — RNDIS gives a 2–5 ms, near-jitter-free link and the jitter buffer
can drop to 5 ms. Plan USB mode as a first-class option, not an afterthought.

---

## Phase 0 — Toolchain and skeleton

**Goal:** a build that compiles Kotlin and C++ together and logs from native code.

1. Add NDK and CMake to `app/build.gradle.kts`: `externalNativeBuild.cmake`, `ndkVersion`,
   `abiFilters = ["arm64-v8a"]` during development (add `armeabi-v7a` at release time).
2. Vendor Oboe. Prefer the Maven artifact `com.google.oboe:oboe:1.9.x` with `prefab = true` — no
   submodule, and CMake finds it via `find_package(oboe REQUIRED CONFIG)`.
3. Create the native source tree:
   ```
   app/src/main/cpp/
     CMakeLists.txt
     jni_bridge.cpp        # the only file that touches JNI
     AudioEngine.{h,cpp}   # owns the Oboe stream and its callback
     RingBuffer.h          # lock-free SPSC
     dsp/                  # Phase 3+
     net/                  # Phase 2+
   ```
4. Compiler flags: `-O3 -fno-math-errno`. Avoid a blanket `-ffast-math` — it breaks denormal and NaN
   handling assumptions inside filters. Set flush-to-zero explicitly (FPCR FZ bit on arm64) at
   audio-thread start instead.
5. Manifest: `RECORD_AUDIO`, `INTERNET`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`, and
   `android:foregroundServiceType="microphone"` on the service added in Phase 1.

**Exit criterion:** `System.loadLibrary("mobimic")` succeeds and a native function returns a string
into Compose.

---

## Phase 1 — Raw, unprocessed capture

This phase determines the ceiling on audio quality. Nothing later can recover what is lost here.

### 1.1 Requesting the unprocessed path

Configure the Oboe input stream:

- `setDirection(Input)`, `setPerformanceMode(LowLatency)`, `setSharingMode(Exclusive)`
- `setFormat(Float)`, `setChannelCount(1)`, `setSampleRate(48000)`,
  `setSampleRateConversionQuality(Medium)`
- `setInputPreset(oboe::InputPreset::Unprocessed)` — the critical line. Fall back to
  `VoiceRecognition` (AGC and NS are off on most devices), then `Generic`.
- `setDataCallback` and `setErrorCallback`. Handle `onErrorAfterClose` by rebuilding the stream; it
  fires on headset/USB-mic hotplug and on some Bluetooth transitions.

From Kotlin, check `AudioManager.getProperty(PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)`. If it
returns `"false"` or null, surface that in the UI — the device applies vendor processing that cannot
be fully defeated, and the user should know before blaming the app.

Belt and braces: after the stream opens, take the session ID and explicitly disable
`AutomaticGainControl`, `NoiseSuppressor`, and `AcousticEchoCanceler` where `isAvailable()` reports
them attached.

### 1.2 Do not assume float gets you the fast path

Requesting `AudioFormat::Float` is convenient, and on some devices it quietly costs an order of
magnitude in latency: the device offers its low-latency input path in 16-bit only, the float open
succeeds anyway on the legacy path, and nothing reports an error. Measured on a realme RMX3842,
float gives a 960-frame burst (20 ms) while `I16` gives 96 frames (2 ms).

`setFormatConversionAllowed(true)` does not save you, because Oboe only falls back to a conversion
wrapper when the direct open *fails*.

So: open with the preferred format, check whether the burst that came back could plausibly be a fast
path (roughly 256 frames or fewer, with `getPerformanceMode()` actually returning `LowLatency`), and
if not, reopen as `I16` and keep whichever burst is smaller. Convert to float in your own callback,
one multiply per sample. Never hardcode a device.

Related trade: allocating an audio session id to force AGC/NS/AEC off disqualifies the stream from
the MMAP path, because effects run in the mixer. Make it a setting rather than a constant, and say
in the UI which trade is in force.

When a configuration misbehaves, probe rather than theorise: open a matrix of configurations, log
what each actually yields, and read the table.

### 1.3 Buffer sizing

Query `getFramesPerBurst()` and set `setBufferSizeInFrames(2 * burst)`. Then add adaptive tuning:
watch `getXRunCount()` and grow the buffer by one burst when it climbs.

For an **input** stream the buffer size is an overrun cushion, not the latency: the callback fires
once per burst regardless, and some devices refuse to shrink the buffer below capacity at all.
Report the burst period and `calculateLatencyMillis()` separately; never multiply the buffer size by
the sample rate and call the answer latency.

### 1.4 Real-time thread discipline

Put this rule list in the callback's header comment. Inside `onAudioReady`, forbidden: `malloc`,
`new`/`delete`, any lock, any JNI call, any file or log I/O, `std::string`, any `std::function` that
may allocate, any syscall. Allowed: arithmetic over preallocated buffers, atomics, and writing to a
lock-free ring.

### 1.5 Verification harness (do not skip)

1. Write captured float frames to a WAV file on the device from a **non**-audio thread fed by the
   ring buffer. Pull with `adb pull` and inspect in Audacity or REAPER.
2. Record a fixed-level sine and a stretch of silence with `Unprocessed` vs `VoiceRecognition` vs
   `Mic`. If the noise floor visibly breathes, or silence is gated to digital black, processing is
   still active.
3. Record the raw noise floor and the level at which the ADC clips. Those two numbers set the gain
   staging in Phase 3.

**Exit criterion:** a WAV pulled off the phone with a flat, continuous, ungated noise floor.

### 1.6 Minimal Python receiver (parallel work)

Same phase, other end: roughly 60 lines that open a UDP socket, dump whatever arrives to a `.raw`
file, and print packets per second. No playback yet. It exists so Phase 2 has a target.

---

## Phase 2 — Transport: UDP, jitter buffer, clock drift

**Goal:** continuous, glitch-free audio arriving in VB-CABLE and visible to Windows as a microphone.

### 2.1 Packet format (fix it now, version it)

```
offset size field
0      4    magic 'MMIC'
4      1    version
5      1    flags        bits0-1: 0=s16le 1=f32le 2=opus; bit2: DSP enabled
6      1    channels
7      1    reserved
8      4    seq          u32, increments per packet, wraps
12     8    frame_index  u64, monotonic capture frame counter — the drift reference
20     4    sample_rate
24     2    num_frames
26     2    reserved
28     ...  payload
```

240 frames (5 ms) of mono `int16` is 480 bytes plus a 28-byte header = 508 bytes. Comfortably under
any MTU; never fragments.

`frame_index` is what makes drift correction possible later. Do not omit it.

### 2.2 Android sender

A dedicated sender thread (never the audio callback) drains the SPSC ring, converts float to int16
with TPDF dither, fills the header, and calls `sendto()`. Bump `SO_SNDBUF`, keep the socket
non-blocking, and count a failed send rather than retrying it.

Hold a `WifiManager.WifiLock(WIFI_MODE_FULL_LOW_LATENCY)` and a partial wake lock while streaming,
inside a foreground service. Without these, Wi-Fi power save inserts 100 ms+ stalls the moment the
screen turns off.

### 2.3 Windows receiver architecture

Three cleanly separated parts:

- **Receiver thread** — blocking `recvfrom`, parse header, convert to `float32` NumPy, reorder by
  `seq`, push into the jitter buffer.
- **Jitter buffer** — a ring holding a target of N ms. Track arrival jitter (a running estimate along
  the lines of RFC 3550) and adjust the target between a floor (5 ms on USB, 15 ms on Wi-Fi) and a
  ceiling (60 ms). On a gap, conceal: repeat the last 5 ms under a cosine fade, then fade to silence
  after two consecutive losses. Never emit a raw discontinuity.
- **PortAudio output callback** — pull exactly `frames_per_buffer` from the ring into a preallocated
  output array. Allocation-free. Target device `CABLE Input (VB-Audio Virtual Cable)`, matched at
  48 kHz to VB-CABLE's own control-panel setting.

### 2.4 Clock drift — the problem most projects ignore

The phone's audio clock and the PC's audio clock differ by roughly 10–100 ppm. At 50 ppm that is
2.4 samples per second: the jitter buffer either starves or overflows within minutes, and an hour of
streaming accumulates around 180 ms of offset.

Two stages:

- **2.4a, get it working:** on underrun insert a 5 ms concealment frame; on overflow drop one.
  Occasionally audible, acceptable for a first pass.
- **2.4b, get it right:** a PI controller on jitter-buffer fill level whose output is a resampling
  ratio around 1.0 (range roughly ±0.005), applied with cubic (Catmull-Rom) fractional-sample
  interpolation in NumPy. One multiply-accumulate group per sample, negligible cost, and the ratio
  moves slowly enough to be inaudible. `frame_index` gives the controller a drift-free reference
  setpoint.

### 2.5 Instrumentation, built now rather than later

Packets received, lost, and reordered; jitter estimate; buffer fill min/max; underruns; measured
end-to-end latency. Print once a second. Every later phase gets debugged with these numbers.

**Exit criterion:** 30 minutes of continuous streaming with zero underruns and a stable buffer fill.

---

## Phase 3 — DSP pipeline (classical)

**Goal:** the SM7B-like character — controlled lows, present mids, no spitting sibilance, no clipping.

### 3.1 Signal chain and its order

```
input gain -> HPF -> gate/expander -> AI NS (Phase 4) -> EQ -> de-esser -> compressor
           -> optional saturation -> makeup gain -> brickwall limiter -> output
```

The HPF goes first so rumble never enters any detector. The gate goes before the compressor so
makeup gain does not lift room noise during pauses. Noise suppression sits before EQ and compression
for the same reason. The limiter is unconditionally last, because everything upstream can add gain.

### 3.2 Block-by-block specification

- **HPF** — 2 cascaded Butterworth biquads (24 dB/oct), corner adjustable 40–120 Hz, default 80 Hz.
- **Gate / downward expander** — RMS detector, threshold, ratio 1:2 to 1:10, roughly 6 dB of
  hysteresis to stop chatter, hold 50 ms, attack 1 ms, release 100–300 ms.
- **EQ** — 5 bands, all built as **TPT / topology-preserving state-variable filters** (Zavalishin
  form) rather than direct-form RBJ biquads. TPT stays stable and click-free when coefficients change
  while running, which is exactly what a user dragging a slider does. Bands: low shelf, two peaking,
  presence shelf (3–6 kHz), high shelf / air.
- **De-esser** — band-split sidechain: bandpass the detector at 5–9 kHz and compress only the high
  band, with threshold and ratio exposed. Split-band, not wideband — wideband de-essing ducks the
  whole voice.
- **Compressor** — feed-forward, log-domain gain computation, soft knee (default 6 dB), threshold,
  ratio, attack 5–20 ms, release 60–250 ms, optional program-dependent release, optional auto makeup.
  For SM7B-style density, two serial stages (a gentle 2:1 then a 4:1) beat one aggressive stage.
- **Saturation (optional)** — `tanh`-style soft clip or an asymmetric cubic, with 2x oversampling to
  keep aliasing out. Off by default.
- **Limiter** — 1.5–3 ms lookahead via a delay line, instantaneous attack, release 50–100 ms, ceiling
  −1.0 dBFS, gain reduction exposed to the meters.

### 3.3 Architecture that makes the DSP tunable without dropouts

- Every block implements the same interface: `prepare(sampleRate, maxBlockSize)`,
  `process(float* buf, int n)`, `reset()`. No allocation outside `prepare`.
- **Parameter passing:** a POD `Params` struct. The UI thread writes into one of two or three buffers
  and publishes an index with an `std::atomic<int>` release store; the audio thread acquire-loads it.
  No locks, no allocation, no torn reads.
- **Smoothing:** every continuous parameter passes through a one-pole smoother (10–50 ms) on the
  audio thread. Un-smoothed gain changes are the number-one source of clicks.
- **Denormals:** enable flush-to-zero via FPCR at thread start. Filter tails decaying into denormals
  cause 10–100x slowdowns and are a classic mystery-glitch source.
- **Bypass:** each block carries an atomic bypass flag, so features can be A/B'd live and profiled
  independently.

### 3.4 Validation

- Unit-test filters offline in a host build, not on the device: impulse response, FFT, compare
  against the intended magnitude response.
- Null test: with everything bypassed, output must be bit-identical to input.
- Sine sweep through the full chain; check THD and the absence of aliasing artifacts.
- Log worst-case callback duration as a percentage of the callback period. Keep it under 50%. That
  number is the CPU budget available to Phase 4.

**Exit criterion:** the full chain running with zero XRuns and a measured CPU headroom figure.

---

## Phase 4 — AI noise suppression

**Goal:** clean speech in a noisy room, without eating the pipeline's CPU or latency budget.

### 4.1 Model choice — ship in this order

1. **RNNoise** (Xiph, BSD, plain C, 48 kHz native, 10 ms hops, around 0.02 GFLOPS). Cheap enough to
   run inline. It proves the plumbing, and on many voices it is already good.
2. **DeepFilterNet 2/3** or **GTCRN** via ONNX Runtime Mobile (XNNPACK, optionally NNAPI). Markedly
   better, 48 kHz native, roughly 20–40 ms algorithmic latency, meaningfully more CPU.
3. **DTLN** via TFLite — a middle option, but 16 kHz-oriented, so it costs two resamplers.

### 4.2 Architecture — decide this before writing the integration

Put every model behind one interface: `INoiseSuppressor { prepare(); process(block); latencyFrames(); }`.
Then support two execution modes:

- **Inline** — if worst-case inference is under roughly 30% of the callback period (RNNoise on arm64
  will be), run it in the audio callback behind an internal 10 ms accumulator.
- **Off-thread** — otherwise run inference on a dedicated worker thread (request `SCHED_FIFO`) fed by
  two SPSC rings, in and out. This trades a fixed, known extra block of latency for an absolute
  guarantee of no dropouts. Heavier models must use this mode.

Because the interface is fixed, swapping models later is a configuration change, not a rewrite.

### 4.3 Validation

Record the same phrase over the same noise bed (keyboard, fan, street) with NS off, RNNoise, and
model 2. Compare by ear and by measured noise reduction in dB, plus speech-band spectral damage.
Cheap objective proxies: segmental SNR against a clean reference, and PESQ/STOI where a clean
reference recording exists.

**Exit criterion:** measurable noise reduction, no XRun regression, and a documented added latency.

---

## Phase 5 — Jetpack Compose UI

**Goal:** the control surface, without letting UI work touch audio timing.

### 5.1 Layering

```
Compose screens -> ViewModel (StateFlow) -> AudioEngineRepository (Kotlin facade)
-> JNI bridge -> native AudioEngine
```

The UI must never call anything that blocks. JNI setters write into the atomic parameter block and
return immediately.

### 5.2 Metering without allocation

Native code writes peak, RMS, gain reduction, XRun count, and packets sent/lost into a preallocated
`ByteBuffer.allocateDirect(...)` shared with Kotlin. A coroutine polls at 30–60 Hz and pushes into a
`StateFlow`. Never call up into Java from the audio thread, and never allocate an array per poll.

### 5.3 Screens

- **Home** — large start/stop control, input level meter with peak hold and clip indicator,
  connection state, measured latency and packet loss, and a clear
  "Unprocessed capture: supported / not supported" badge.
- **Mixer** — EQ curve on a Compose `Canvas` with draggable band handles, gain-reduction meter,
  compressor / gate / de-esser / limiter controls, per-block bypass switches.
- **Connection** — target IP and port, discovered devices, Wi-Fi vs USB mode, wire format,
  buffer size.
- **Presets** — save and load named presets as JSON via DataStore; ship three or four factory
  presets (Broadcast, Podcast, Meeting, Flat).

### 5.4 Service and lifecycle

Foreground service with `foregroundServiceType="microphone"`, an ongoing notification carrying a stop
action, wake lock plus `WIFI_MODE_FULL_LOW_LATENCY` Wi-Fi lock, and correct behaviour on audio focus
loss, incoming calls, and stream disconnect (Oboe `onErrorAfterClose` triggers a rebuild).

---

## Phase 6 — Polish and hardening

- **Discovery** — Android NSD / mDNS advertising `_mobimic._udp`; the Windows receiver browses and
  auto-fills the IP. Removes the most annoying manual step.
- **USB mode** — support and document USB tethering; detect the RNDIS interface and automatically
  drop the jitter-buffer target. This is the studio-grade mode.
- **Opus** — libopus via CMake, 48 kHz mono, 10 ms frames, `APPLICATION_AUDIO` (not VOIP; VOIP mode
  applies its own processing), 96–128 kbit/s, FEC on, DTX off. Worth it only on congested Wi-Fi, and
  it adds about 6.5 ms algorithmic latency. Raw PCM stays the default.
- **Security** — the stream is unauthenticated UDP on the LAN. Add an optional shared-secret HMAC
  over the header plus a nonce, so a stranger on the same network cannot inject audio into the user's
  microphone. Document clearly that this is LAN-only and not a substitute for a trusted network.
- **Windows packaging** — PyInstaller one-file build, tray icon, a `config.toml`, optional autostart,
  and a first-run check for VB-CABLE with a download link if it is missing.
- **Robustness** — reconnect on network change, handle Wi-Fi to USB switching, thermal throttling
  behaviour, battery drain measurement, and behaviour on rotation and backgrounding.
- **Release build** — R8 rules for JNI-referenced classes, `armeabi-v7a` added to the ABI list, native
  symbols uploaded, ProGuard-safe metering classes.

---

## Cross-cutting risks

| Risk | Where it appears | Mitigation |
|---|---|---|
| Vendor DSP cannot be fully disabled on some phones | Phase 1 | Detect and report it; document the behaviour rather than pretending it is off. |
| A device grants the fast path only in one sample format | Phase 1 | Open, measure the burst, reopen in the other format, keep the smaller. Never assume float. |
| Forcing effects off costs the MMAP path | Phase 1 | A session id disqualifies MMAP. Make it a setting, default off, and say in the UI which trade is in force. |
| Wi-Fi power save stalls when the screen is off | Phase 2 | Foreground service plus low-latency Wi-Fi lock, tested with the screen off. |
| Clock drift | Phase 2 | `frame_index` in the header from day one; PI-controlled resampler in 2.4b. |
| Python GC or GIL hitches in the output callback | Phase 2 | Preallocate everything; no allocation and no logging in the callback; consider `gc.freeze()`. |
| DSP or NS overruns the callback | Phase 3 and 4 | Worst-case callback timing logged continuously; off-thread mode for heavy models. |
| Parameter changes clicking | Phase 3 | TPT filters plus one-pole smoothing on every continuous parameter. |
| Denormal stalls | Phase 3 | Flush-to-zero on the audio thread. |

## Build order

Phase 0, then 1 (capture plus WAV proof), then 1.5 (dumb receiver), then 2 (transport and drift),
then 3 (DSP), 4 (NS), 5 (UI), 6 (polish).

Phase 1.5 and the Python side of Phase 2 can be built in parallel with the Android work. Everything
else is strictly sequential, because each phase's exit criterion is the next phase's assumption.
