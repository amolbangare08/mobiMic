# mobiMic

Turns an Android phone into a low-latency microphone for Windows. The phone captures
unprocessed audio, runs a broadcast-style DSP chain on it, and streams it over the LAN;
the PC feeds it into a virtual audio cable so Windows treats it as an ordinary recording
device.

See [ROADMAP.md](ROADMAP.md) for the build plan and
[docs/DEVICE-TEST-RESULTS.md](docs/DEVICE-TEST-RESULTS.md) for measured results.

---

## One-time setup

### 1. Windows: VB-CABLE

Install [VB-CABLE](https://vb-audio.com/Cable/) (admin, then reboot).

Its installer makes itself the **default playback and recording device**, which silently
routes all your system audio into the cable. Set your real speakers and microphone back as
defaults in Windows sound settings. mobiMic addresses the cable by name and does not need
to be the default.

Check that the cable runs at 48 kHz: Sound settings, `CABLE Input` properties, Advanced.
Anything else makes Windows resample every sample; the receiver warns if it sees this.

### 2. Windows: Python dependencies

```bash
pip install -r pc/requirements.txt
```

### 3. Phone: install the app

With the phone connected over USB and USB debugging on:

```bash
./gradlew :app:installDebug
```

Grant the microphone permission when the app asks. On ColorOS and similar, `adb shell pm
grant` is blocked, so this has to be done on the phone.

---

## Running it

### On the PC

```bash
python pc/receiver.py
```

That defaults to `--device "CABLE Input"`. It prints one line a second:

```
199.9 pkt/s  812.4 kbit/s  buffer 48.0 ms (target 50.0) jitter 1.4 ms  ratio 1.00007  lost 0  reorder 0  under 0  over 0
```

What to look at:

- **lost / reorder** — network health. Should be 0 on a decent Wi-Fi network.
- **under** — buffer starvation, heard as brief glitches. Should stop climbing within the
  first minute; the receiver raises its own target until it does, and remembers the result
  in `pc/receiver_state.json`.
- **ratio** — clock drift correction. Should sit near 1.0000. If it reads `RAILED` for
  more than a few seconds the target is unreachable and the receiver will raise it.
- **buffer vs target** — should track within a few ms.

### On the phone

1. Open mobiMic.
2. **Connect** tab: press **Scan for receivers**, then tap the one that appears.
   Typing an address by hand still works if you prefer.
3. **Home** tab: Start capture. Streaming starts with it.
4. **Mixer** tab: pick a preset (Broadcast, Podcast, Meeting, Flat) and adjust.

The scan broadcasts on every interface and the receiver answers, so you do not need to
know the PC's address - which matters most over USB, where the subnet changes every time
you plug the cable in.

If you would rather set it manually, `ipconfig` on the PC gives the address. Over Wi-Fi
both devices must be on the same subnet.

---

## Over USB instead of Wi-Fi

Wi-Fi works, but the receiver's jitter buffer is most of the end-to-end latency, and that
buffer is sized for Wi-Fi's jitter. A cable has almost none, so the buffer can be far
smaller.

1. Plug the phone into the PC.
2. On the phone, turn on **USB tethering** (on ColorOS: Settings, Connection & sharing,
   Personal hotspot, USB tethering). Windows will add a new network adapter.
3. In mobiMic's **Connect** tab, set the link to **USB** (or leave it on **Auto**, which
   prefers the cable whenever one is up), then **Scan for receivers**.
4. Start capture as usual.

The Connect tab lists the interfaces it can see, so a USB link shows up there as
`rndis0` or `usb0` with an address in `192.168.42.x`. Once streaming, it also shows which
link is actually carrying the audio - useful when both are up and you want to be sure.

The receiver notices a sender on the USB subnet and keeps a **separate learned buffer
target** for it, so the figure it learned over Wi-Fi does not follow the cable across and
make it needlessly slow.

**On USB mode:** picking **USB** rather than **Auto** means the app will refuse to fall
back to Wi-Fi. That is deliberate - a silent fallback would leave you wondering why the
latency was still high.

### In the app you want to use the mic

Set the input device to **CABLE Output (VB-Audio Virtual Cable)** — in Discord, OBS, Zoom,
your DAW, wherever. That is the other end of the cable the receiver is feeding.

---

## Checking it works

Watch the receiver's `pkt/s`: 200.0 is correct for the default 240-frame packets at
48 kHz. Then look at the phone's Home tab — the input meter should move when you speak,
and `XRuns` and `Frames dropped` should both stay at 0.

To confirm Windows itself is receiving audio, open Sound settings, Recording, and watch the
level meter on `CABLE Output`.

---

## Driving it from adb

Debug builds export the capture service so the whole thing can be run without touching the
screen. Useful when the phone is in use, or for scripted testing.

```bash
adb shell am start-foreground-service -n com.amol.mobimic/.service.MicService -a com.amol.mobimic.START
```

| Action | Effect |
|---|---|
| `START` / `STOP` | Start or stop capture (and streaming) |
| `STREAM_START` / `STREAM_STOP` | Streaming only, leaving capture running |
| `RECORD_START --es source raw` | Record a WAV, tapped before the DSP chain |
| `RECORD_START --es source processed` | Record what actually goes on the wire |
| `RECORD_STOP` | Stop recording |
| `APPLY_PRESET --es preset Broadcast` | Apply a factory preset |
| `SET_WIRE_FORMAT --es format PCM_S16` | `PCM_S16` or `PCM_F32` |
| `SET_EFFECT_OVERRIDE --es enabled true` | See "Effect override" below |
| `SET_LINK --es link USB` | `AUTO`, `USB` or `WIFI` |
| `DISCOVER` | Log the visible interfaces and any receivers that answer |
| `PROBE_PATHS` | Log a table of what each stream configuration yields |
| `LOG_STATS` | Print the live counters to logcat |

Watch the app's own log with:

```bash
adb logcat -s mobiMic
```

Recordings land in the app's external files directory:

```bash
adb pull /storage/emulated/0/Android/data/com.amol.mobimic/files ./captures
```

---

## Settings worth understanding

### Effect override

`forceEffectsOff` allocates an audio session so the app can attach to the platform's AGC,
noise suppressor and echo canceller and force them off. It is **off by default**, because
AAudio will not grant a session id and the MMAP low-latency path at the same time.

Leave it off unless the Home tab shows the capture sounding processed - gated pauses,
levels riding up and down - on a device that ignores the `Unprocessed` preset. Then the
trade is worth making.

### Wire format

`PCM_S16` by default: 812 kbit/s, with dither applied on the phone. `PCM_F32` doubles the
bandwidth and is there for debugging, not for quality - the chain's output sits far below
the point where 16 bits would limit anything.

---

## When it does not work

**No packets at the receiver.** Both devices on the same subnet? Check the phone's
Connect tab shows the right address, and that Windows Firewall is not blocking UDP 47001
inbound for Python.

**Scan finds nothing.** The receiver answers probes on UDP 47002, so that port needs to be
allowed inbound for Python too. Over USB, check that tethering is actually on and that
Windows brought the adapter up - the Connect tab lists what the phone can see.

**`under` keeps climbing.** The buffer target is still too low. It raises itself once per
underrun; give it a minute. To skip the learning, start with `--target-ms 50`.

**Glitches when the screen turns off.** The service holds a wake lock and a low-latency
Wi-Fi lock, but aggressive vendor battery management can still park the radio. Exempt
mobiMic from battery optimisation in Android settings.

**Audio sounds thin or over-processed.** Check the Mixer tab. `Flat` bypasses everything
except the high-pass and limiter, which is the right place to start when judging the raw
capture.

**Latency feels high.** On a good network the phone contributes about 4 ms and the jitter
buffer 50-60 ms, so the buffer is where to look. USB tethering removes most of the
network's contribution and allows a much smaller buffer.
