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
- Android shell kept dependency-light

The project currently targets `compileSdk 36` / `targetSdk 36` and uses JDK 17. This is intentional while the core is being established so CI does not depend on API 37 tooling.

## Current brick

**Brick #8: WAV export / offline render**

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
- preview only the selected range after applying gain
- redraw waveform amplitude immediately from clip gain while keeping the cached source waveform untouched
- accept finite dB input without an arbitrary per-apply cap
- undo and redo edits
- export the current edited timeline through Android's document picker

Export uses a UI-independent `TimelineRenderer` that walks the same clip positions, source ranges, silence gaps, and per-clip gain used by playback. The Activity only chooses the destination URI and reports progress; it does not know how to render samples or construct a WAV file.

Brick #8 exports standard 16-bit PCM WAV while preserving the current project sample rate and mono/stereo layout. Because the complete frame count is known before rendering, `Pcm16WavEncoder` writes the final RIFF header up front and works with document-provider streams that cannot seek. Classic RIFF's 4 GiB container limit is enforced; RF64 comes later.

The exported file spans from the first audible timeline frame through the final clip, matching the current playback/preview window. Silence gaps between clips are rendered as actual zero samples. Gain is baked into the exported PCM using the same `GainProcessor` semantics as playback, including the current full-scale clamp.

Currently supported input WAV sample encodings:

- PCM integer: 8, 16, 24, and 32-bit
- IEEE float: 32 and 64-bit
- WAVE_FORMAT_EXTENSIBLE when its sub-format is PCM or IEEE float

Playback and export are still intentionally limited to one mono/stereo track at one native sample rate. Mixing, resampling, fades, additional codecs, and RF64 come later.

## First useful target

Open audio → waveform → select → trim / split / move / amplify → undo / redo → export.

Brick #8 reaches that first end-to-end target for WAV files.

Quick Trim, Quick Amplify, and Quick Join will eventually sit on top of those exact same operations.

## Build

Open the repository in Android Studio, or use Gradle 9.5.0 with JDK 17:

```bash
gradle testDebugUnitTest assembleDebug
```

A Gradle wrapper will be committed once generated from a normal Gradle installation; until then CI installs the pinned Gradle version explicitly.

## Licence

GPL-3.0-or-later. See `LICENSE`.
