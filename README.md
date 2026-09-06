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
- Android shell kept dependency-light

The project currently targets `compileSdk 36` / `targetSdk 36` and uses JDK 17. This is intentional while the core is being established so CI does not depend on API 37 tooling.

## Current brick

**Brick #6: horizontal clip movement**

The Android shell can now:

- pick and decode a real WAV
- build and render its waveform pyramid
- play/pause/stop through Android `AudioTrack`
- tap the waveform to seek
- drag the waveform body to create a selection
- trim, split, and non-ripple delete without modifying the WAV
- render and play multiple sequential clips plus silence gaps
- drag the `C#` strip at the top of a clip to move it horizontally
- preview movement before release
- prevent clips from overlapping or crossing neighbours
- undo and redo a completed move as one edit

Clip movement currently stays on one track. Neighbouring clips are hard walls until the project gains a real mixer/overlap model. The waveform-body selection gesture and vertical page scrolling remain separate from the clip-header movement gesture.

Currently supported WAV sample encodings:

- PCM integer: 8, 16, 24, and 32-bit
- IEEE float: 32 and 64-bit
- WAVE_FORMAT_EXTENSIBLE when its sub-format is PCM or IEEE float

Playback is still intentionally limited to one mono/stereo track at its native sample rate. Mixing, resampling, effects UI, and export come later.

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
