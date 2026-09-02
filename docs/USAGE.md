# Usage

Full setup, day-to-day operation, tuning and troubleshooting. For what the project is and
why, see the [README](../README.md); for measured results see
[DEVICE-TEST-RESULTS.md](DEVICE-TEST-RESULTS.md).

---

## One-time setup

### Windows: VB-CABLE

Install [VB-CABLE](https://vb-audio.com/Cable/) (admin, then reboot).

Its installer makes itself the **default playback and recording device**, which silently
routes all your system audio into the cable. Set your real speakers and microphone back as
defaults in Windows sound settings. mobiMic addresses the cable by name and does not need
to be the default.

Check the cable runs at 48 kHz: Sound settings → `CABLE Input` → Properties → Advanced.
Anything else makes Windows resample every sample; the receiver prints a warning if it
sees a mismatch.

### Windows: Python dependencies

```bash
pip install -r pc/requirements.txt
```

`numpy` and `sounddevice` are required. `zeroconf` is optional and only enables mDNS
advertising; discovery works without it.

### Phone: install the app

With the phone connected over USB and USB debugging enabled:

```bash
./gradlew :app:installDebug
```

Grant the microphone permission when the app asks. On ColorOS and some other vendor
builds, `adb shell pm grant` is blocked, so this has to be done on the phone itself.

---

## Running it

### On the PC

```bash
python pc/receiver.py
```

Or double-click `start-receiver.bat`. It defaults to `--device "CABLE Input"` and prints
one status line per second:

```
199.9 pkt/s  812.4 kbit/s  buffer 48.0 ms (target 50.0) jitter 1.4 ms  ratio 1.00007  lost 0  reorder 0  under 0  over 0
```

| Field | What it means |
|---|---|
| `pkt/s` | 200.0 is correct for 240-frame packets at 48 kHz |
| `lost` / `reorder` | Network health. Should be 0 on a decent link |
| `under` | Buffer starvation, heard as brief glitches. Should stop climbing within a minute |
| `ratio` | Clock drift correction. Should sit near 1.0000. `RAILED` means the target is unreachable and the receiver is about to raise it |
| `buffer` vs `target` | Should track within a few ms |

Useful flags:

| Flag | Effect |
|---|---|
| `--list-devices` | Print every audio device and exit |
| `--device "name"` | Output device, matched by substring. Prefers WASAPI automatically |
| `--target-ms 50` | Start at a known-good jitter target instead of learning it |
| `--no-audio` | Run the whole receive path without opening an output device |
| `--exclusive` | WASAPI exclusive mode. Measured *worse* on VB-CABLE; useful on real hardware |
| `--dump file.f32` | Write the played audio as raw float32 |
| `--no-discovery` | Stop answering discovery probes from the phone |

### On the phone

1. Open mobiMic.
2. **Connect** tab → **Scan for receivers** → tap the result. Typing an address by hand
   still works.
3. **Level** tab → **Start**. Streaming begins with capture.
4. **Mixer** tab → pick a preset and adjust.

### In the app that needs the microphone

Set the input device to **CABLE Output (VB-Audio Virtual Cable)**.

In Audacity specifically: Audio Host `Windows WASAPI`, Recording Device
`CABLE Output`, Channels `1 (Mono)`, Project Rate `48000`. Do not enable Software
Playthrough — it would feed audio back into the same cable.

---

## Over USB instead of Wi-Fi

The receiver's jitter buffer is most of the end-to-end latency, and it is sized for the
link's jitter. A cable has far less, so the buffer can be smaller.

1. Plug the phone into the PC.
2. Turn on **USB tethering** (ColorOS: Settings → Connection & sharing → Personal hotspot
   → USB tethering). Windows adds a network adapter.
3. Connect tab → link **USB**, or leave it on **Auto** which prefers the cable whenever
   one is up. Then **Scan for receivers**.

The Connect tab lists the interfaces the phone can see, so a USB link appears as `rndis0`
or `usb0`. Once streaming, it also shows which link is actually carrying audio.

The receiver keeps a **separate learned buffer target per link**, so the figure it learned
over Wi-Fi does not follow the cable across and make it needlessly slow.

Choosing **USB** rather than **Auto** makes the app refuse to fall back to Wi-Fi. That is
deliberate: a silent fallback would leave you wondering why latency was still high.

---

## Settings worth understanding

### Effect override

`forceEffectsOff` allocates an audio session so the app can attach to the platform's AGC,
noise suppressor and echo canceller and force them off.

It is **off by default**, because AAudio will not grant a session id and the MMAP
low-latency path at the same time — you get one or the other. On a device that honours the
`Unprocessed` preset the override buys nothing and costs latency.

Turn it on if the Level tab shows the capture sounding processed (gated pauses, levels
riding up and down) on a device that ignores `Unprocessed`.

### Wire format

`PCM_S16` by default: 812 kbit/s, dithered on the phone. `PCM_F32` doubles the bandwidth
and exists for debugging, not for quality — the chain's output sits far below the point
where 16 bits would limit anything.

### Presets

| Preset | For |
|---|---|
| Broadcast | The default. Rumble gone, presence lift, gentle 3:1 compression |
| Podcast | Softer compression, flatter EQ |
| Meeting | Noise suppression on, tighter gate |
| Flat | Everything bypassed except high-pass and limiter — the honest reference |

---

## Driving it from adb

Debug builds export the capture service so the whole thing can be operated without
touching the screen. Useful when the phone is in use, or for scripted testing.

```bash
adb shell am start-foreground-service -n com.amol.mobimic/.service.MicService -a com.amol.mobimic.START
```

| Action | Effect |
|---|---|
| `START` / `STOP` | Start or stop capture (and streaming) |
| `STREAM_START` / `STREAM_STOP` | Streaming only, leaving capture running |
| `RECORD_START --es source raw` | Record a WAV tapped *before* the DSP chain |
| `RECORD_START --es source processed` | Record what actually goes on the wire |
| `RECORD_STOP` | Stop recording |
| `APPLY_PRESET --es preset Broadcast` | Apply a factory preset |
| `SET_WIRE_FORMAT --es format PCM_S16` | `PCM_S16` or `PCM_F32` |
| `SET_LINK --es link USB` | `AUTO`, `USB` or `WIFI` |
| `SET_EFFECT_OVERRIDE --es enabled true` | Toggle the AGC/NS/AEC override |
| `DISCOVER` | Log visible interfaces and any receivers that answer |
| `PROBE_PATHS` | Log a table of what each stream configuration actually yields |
| `LOG_STATS` | Print the live counters to logcat |

```bash
adb logcat -s mobiMic
```

Recordings land in the app's external files directory:

```bash
adb pull /storage/emulated/0/Android/data/com.amol.mobimic/files ./captures
```

`PROBE_PATHS` is the tool to reach for when a device gives worse latency than expected. It
opens a matrix of stream configurations and logs the burst size each one actually yields,
which is how the 20 ms → 2 ms improvement on the development phone was found.

---

## Troubleshooting

**No packets at the receiver.** Are both devices on the same subnet? Check the Connect tab
shows the right address, and that Windows Firewall is not blocking UDP 47001 inbound for
Python.

**Scan finds nothing.** The receiver answers probes on UDP 47002, so that port also needs
to be allowed inbound for Python. Over USB, check tethering is actually on and Windows
brought the adapter up — the Connect tab lists what the phone can see.

**`under` keeps climbing.** The buffer target is too low. It raises itself once per
underrun and settles within a minute; the learned figure is remembered per device and link
in `pc/receiver_state.json`. To skip the learning, start with `--target-ms 50`.

**`ratio` reads RAILED.** The target is unreachable, usually because the host audio period
is larger than the buffer. The receiver raises the target automatically after three
seconds. If it persists, raise `--target-ms`.

**Glitches when the screen turns off.** The service holds a wake lock and a low-latency
Wi-Fi lock, but aggressive vendor battery management can still park the radio. Exempt
mobiMic from battery optimisation in Android settings.

**Audio sounds thin or over-processed.** Check the Mixer tab. `Flat` bypasses everything
except the high-pass and limiter, which is the right place to start when judging the raw
capture.

**"Unprocessed source: Not supported".** The device applies vendor processing that cannot
be fully disabled. Try the effect override, and compare a `Flat` recording against a
processed one to hear what it is doing.

**Latency feels high.** Check the phone's own figure on the Level tab first — if it reads
about 4 ms, the phone is not the problem and the receiver's jitter buffer is. Use USB
tethering, and see the buffer target the receiver settles on.
