# Phase 1 verification procedure

Phase 1 has one job: prove the capture path is untouched. Everything downstream inherits
whatever this stage produces, so the exit criterion is a measurement, not an opinion.

## 1. Check what the device admits to

Launch the app and read the **Capture path** card:

- `Unprocessed source: supported` — the device exposes the raw path.
- `Unprocessed source: NOT supported` — vendor processing is unavoidable. Record the
  comparison files anyway (step 3) and note the limitation; it caps the achievable quality
  on this handset.
- `Preset in use` should read `UNPROCESSED` once capture starts. Anything else means the
  stream fell back.
- AGC / noise suppressor / echo canceller should each read `off` or `not present`.
  `STUCK ON` means the platform refused the override.

## 2. Start capture and watch the stream card

With capture running and the app in the foreground:

- `XRuns` should stay at 0. If it climbs, the engine grows the buffer by one burst per
  second until it stops; note the buffer size it settles at.
- `Frames dropped` must stay 0. Non-zero means the drain thread cannot keep up, which is a
  bug, not a tuning issue.
- `Worst callback load` is the headroom figure. Whatever it reads here is what the Phase 3
  DSP chain has to fit inside — aim to keep the total under 50%.

## 3. Record the comparison set

Record roughly 20 seconds for each of these, using **Record WAV**:

1. Silence in a quiet room.
2. Speech at normal distance.
3. A steady tone played near the phone (a phone tone generator is fine).

Pull them off the device:

```bash
adb shell ls /sdcard/Android/data/com.amol.mobimic/files
```

```bash
adb pull /sdcard/Android/data/com.amol.mobimic/files ./captures
```

## 4. What to look for

Open the files in Audacity or REAPER.

- **The silence file is the important one.** The noise floor must be continuous and flat.
  If it fades toward digital black during pauses, a gate is running. If its level visibly
  rises and falls, AGC is running.
- **The speech file** should have consistent peaks for consistent delivery. Peaks that get
  pulled back after a loud syllable mean automatic level control.
- **The tone file** should show a clean single peak in the spectrum. Extra harmonics that
  are not in the source mean distortion or processing.
- Note the **noise floor level in dBFS** and the **level at which the input clips**. Those
  two numbers set the gain staging for the Phase 3 compressor and limiter.

## 5. Exit criterion

A silence capture with a flat, continuous, ungated noise floor, zero dropped frames, and a
recorded callback-load figure. Phase 2 assumes all three.
