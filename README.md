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
- configuration-retained live editor history and waveform cache
- Android shell kept dependency-light

The project currently targets `compileSdk 36` / `targetSdk 36` and uses JDK 17. This is intentional while the core is being established so CI does not depend on API 37 tooling.

## Current brick

**Brick #9.1: multisource polish**

Brick #9.1 closes three issues found during device testing before Quick Join is built on top:

- Trim now targets any one clip that fully contains the selection, even when other clips and sources exist elsewhere on the track. A selection spanning a gap or multiple clips remains intentionally ambiguous and does not enable Trim.
- Android configuration recreation retains the live `ProjectEditor`, undo/redo stacks, waveform cache, source-format metadata, and current selection. Rotating the device therefore no longer destroys edit history or forces waveform caches to be rebuilt.
- Per-source WAV information is visible again, including filename, sample rate, mono/stereo layout, bit depth, and PCM/float encoding.

The Android shell can:

- open a WAV as a new project
- add another WAV without replacing the current project
- assign every imported file an independent source identity, even when the same document is added twice
- append the new source after the current last clip on the existing track
- build and retain a separate waveform pyramid for each source
- play across source boundaries using the existing timeline playback engine
- trim one selected clip in a multisource project
- split, delete, move, and amplify imported clips with the same editor operations
- undo and redo edits across screen rotation
- export all participating sources through the same offline renderer

`AddSourceClip` remains a real editor operation. It adds the immutable source and its first clip to project state as one history entry, so Undo removes both and Redo restores both. The retained configuration session also preserves waveform data for a source that currently exists only in Redo history, so `Add B → Undo → rotate → Redo` remains valid.

Process death is intentionally different from configuration recreation. Saved-instance reconstruction restores the current project and reopens its source files, but edit history is not yet durable project data and is not promised after the app process is killed.

Brick #9 still intentionally requires imported WAVs to match the project's current sample rate and channel count. A 48 kHz stereo project accepts another 48 kHz stereo WAV; mismatched rates or channel layouts are refused until resampling and channel conversion exist. Clips remain non-overlapping on one track for now.

Currently supported input WAV sample encodings:

- PCM integer: 8, 16, 24, and 32-bit
- IEEE float: 32 and 64-bit
- WAVE_FORMAT_EXTENSIBLE when its sub-format is PCM or IEEE float

Export remains standard 16-bit PCM WAV, preserving the project sample rate and mono/stereo layout. Playback and export are still intentionally limited to one non-overlapping track at one native sample rate. Mixing, resampling, channel conversion, fades, additional codecs, and RF64 come later.

## First useful target

Open audio → waveform → select → trim / split / move / amplify → undo / redo → export.

Brick #8 reached that end-to-end target. Brick #9 extended the same path to multiple input sources. Brick #9.1 seals the device-test regressions before Quick Join becomes the friendly front door to that machinery.

Quick Trim, Quick Amplify, and Quick Join will sit on top of these same editor and renderer operations.

## Build

Open the repository in Android Studio, or use Gradle 9.5.0 with JDK 17:

```bash
gradle testDebugUnitTest assembleDebug
```

A Gradle wrapper will be committed once generated from a normal Gradle installation; until then CI installs the pinned Gradle version explicitly.

## Licence

GPL-3.0-or-later. See `LICENSE`.
