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
- offline timeline renderer shared with export
- seekless PCM16 RIFF/WAVE encoder for Android document providers
- multiple immutable WAV sources in one project
- Android shell kept dependency-light

The project currently targets `compileSdk 36` / `targetSdk 36` and uses JDK 17. This is intentional while the core is being established so CI does not depend on API 37 tooling.

## Current brick

**Brick #9: second-source WAV import**

The Android shell can now:

- open a WAV as a new project
- add another WAV without replacing the current project
- assign every imported file an independent source identity, even when the same document is added twice
- append the new source after the current last clip on the existing track
- build and retain a separate waveform pyramid for each source
- play across source boundaries using the existing timeline playback engine
- edit imported clips with the same split, delete, move, amplify, undo, and redo operations
- export all participating sources through the same Brick #8 offline renderer
- persist all current source locations, source identities, and clip-to-source references across Android configuration recreation

`AddSourceClip` is a real editor operation. It adds the immutable source and its first clip to project state as one history entry, so Undo removes both and Redo restores both. Quick Join can later call this same operation instead of maintaining a second join implementation.

Brick #9 intentionally requires imported WAVs to match the project's current sample rate and channel count. A 48 kHz stereo project accepts another 48 kHz stereo WAV; mismatched rates or channel layouts are refused with a clear message until resampling and channel conversion exist. Clips remain non-overlapping on one track for now.

Source IDs are independent from Android document URIs. This means the same WAV can be imported more than once as distinct project sources while all decoded audio still comes from the original persisted document locations.

Currently supported input WAV sample encodings:

- PCM integer: 8, 16, 24, and 32-bit
- IEEE float: 32 and 64-bit
- WAVE_FORMAT_EXTENSIBLE when its sub-format is PCM or IEEE float

Export remains standard 16-bit PCM WAV, preserving the project sample rate and mono/stereo layout. Playback and export are still intentionally limited to one non-overlapping track at one native sample rate. Mixing, resampling, channel conversion, fades, additional codecs, and RF64 come later.

## First useful target

Open audio → waveform → select → trim / split / move / amplify → undo / redo → export.

Brick #8 reached that end-to-end target. Brick #9 extends the same path to more than one input source and lays the direct foundation for Quick Join.

Quick Trim, Quick Amplify, and Quick Join will sit on top of these same editor and renderer operations.

## Build

Open the repository in Android Studio, or use Gradle 9.5.0 with JDK 17:

```bash
gradle testDebugUnitTest assembleDebug
```

A Gradle wrapper will be committed once generated from a normal Gradle installation; until then CI installs the pinned Gradle version explicitly.

## Licence

GPL-3.0-or-later. See `LICENSE`.
