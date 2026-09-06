# Modesty

Modesty is an Android-first, free and open-source audio editor focused on making ordinary audio jobs fast without giving up a real editing core.

The guiding rule is simple:

> Quick tools are views into the same editor engine, not separate watered-down implementations.

## Foundation

The foundation is being built and tested one brick at a time:

- immutable project / track / clip metadata
- source audio stays separate from edit metadata
- edit transactions with undo and redo
- DSP contracts kept independent from UI
- multi-resolution waveform cache
- real WAV decoding
- hardware-derived playback playhead
- non-overlapping multi-clip timeline with real silence gaps
- horizontal clip movement with explicit overlap constraints
- per-clip gain routed through the shared `GainProcessor`
- gain-aware waveform display without rebuilding the source cache
- Android shell kept dependency-light

The project currently targets `compileSdk 36` / `targetSdk 36` and uses JDK 17. This is intentional while the core is being established so CI does not depend on API 37 tooling.

## Current brick

**Brick #7.1: gain-aware waveform + uncapped amplify UI**

The Android shell can now:

- pick and decode a real WAV
- build and render its waveform pyramid
- play/pause/stop through Android `AudioTrack`
- tap the waveform to seek
- drag the waveform body to create a selection
- trim, split, and non-ripple delete without modifying the WAV
- render and play multiple sequential clips plus silence gaps
- drag the `C#` strip at the top of a clip to move it horizontally
- prevent clips from overlapping or crossing neighbours
- enter a dB value and amplify/attenuate any selected timeline range
- split gain boundaries automatically so only selected audio changes
- preview only the selected range after applying gain
- redraw waveform amplitude immediately from clip gain while keeping the cached source waveform untouched
- accept any finite dB input that converts to a finite internal gain instead of imposing an arbitrary per-apply cap
- undo and redo amplification as one edit
- preserve clip gain across Android configuration recreation

Amplify does not rewrite PCM. It converts the UI dB value to a linear multiplier, stores the result in clip metadata, then playback routes decoded float PCM through the same UI-independent `GainProcessor` established in the foundation. Quick Amplify and the later full editor therefore share the same path.

The waveform renderer now multiplies cached source peaks by clip gain at draw time and clamps the visual result to full scale, matching the current playback clamp. Large positive gain is allowed but warned because it can hard-clip when the source has insufficient headroom. Values beyond the finite range representable by the engine are rejected instead of producing invalid gain metadata.

Currently supported WAV sample encodings:

- PCM integer: 8, 16, 24, and 32-bit
- IEEE float: 32 and 64-bit
- WAVE_FORMAT_EXTENSIBLE when its sub-format is PCM or IEEE float

Playback is still intentionally limited to one mono/stereo track at its native sample rate. Mixing, resampling, fades, additional codecs, and export come later.

## First useful target

Open audio → waveform → select → trim / split / move / amplify → undo / redo → export.

Quick Trim, Quick Amplify, and Quick Join will eventually sit on top of those exact same operations.

## Build

Open the repository in Android Studio, or use Gradle 9.5.0 with JDK 17:

```bash
gradle testDebugUnitTest assembleDebug
```

A Gradle wrapper will be committed once generated from a normal Gradle installation; until then CI installs the pinned Gradle version explicitly.

## Licence

GPL-3.0-or-later. See `LICENSE`.
