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
- non-overlapping multi-clip timeline playback
- Android shell kept dependency-light

The project currently targets `compileSdk 36` / `targetSdk 36` and uses JDK 17. This is intentional while the core is being established so CI does not depend on API 37 tooling.

## Current brick

**Brick #5: split + delete + multi-clip timeline**

The Android shell can now:

- pick and decode a real WAV
- build and render its waveform pyramid
- play/pause/stop through Android `AudioTrack`
- tap the timeline to seek
- drag across the timeline to create a selection
- trim a single clip nondestructively
- split clips at both boundaries of a selection
- delete audio under a timeline selection without touching the source file
- render and play multiple non-overlapping clips on one track
- render deleted regions as timeline gaps and play those gaps as silence
- undo and redo timeline edits

Split and delete are metadata edits. The source WAV and its waveform pyramid remain immutable. Deleting a range currently leaves silence in the timeline; ripple delete is intentionally a later operation.

Currently supported WAV sample encodings:

- PCM integer: 8, 16, 24, and 32-bit
- IEEE float: 32 and 64-bit
- WAVE_FORMAT_EXTENSIBLE when its sub-format is PCM or IEEE float

Playback still deliberately supports one track, mono/stereo output, non-overlapping clips, and sources whose sample rate matches the project timeline rate. Mixing, resampling, effects UI, moving clips, and export come later.

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
