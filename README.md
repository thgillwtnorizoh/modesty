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
- Quick Join as a thin workflow over the same editor operations
- Android shell kept dependency-light

The project currently targets `compileSdk 36` / `targetSdk 36` and uses JDK 17. This is intentional while the core is being established so CI does not depend on API 37 tooling.

## Current brick

**Brick #10: Quick Join**

Quick Join is the first user-facing quick tool built on the editor core instead of beside it.

The flow is deliberately small:

1. tap **Quick Join**
2. choose WAV A
3. choose WAV B
4. preview or make normal edits
5. export through the existing WAV renderer

The first selection opens a normal one-source Modesty project. The second selection is appended with the same `AddSourceClip` editing operation used by ordinary **Add WAV**. `appendWholeSourceAtEndOperation` only calculates the normal full-source clip and its exact timeline position after the current final clip; it does not render, concatenate, or rewrite PCM itself.

After the second picker finishes, there is no separate Quick Join document type or playback mode. The result is an ordinary two-source project, so Trim, Split, Delete, Move, Amplify, Undo/Redo, waveform rendering, playback, rotation retention, and Brick #8 export all continue through the existing code paths.

Cancellation is intentionally non-destructive. Cancel before choosing A and the existing project remains untouched. Cancel before choosing B and A remains open as a normal editable project.

Quick Join inherits Brick #9's current format constraint: A and B must have matching sample rate and channel count. Resampling and channel conversion remain future work rather than hidden conversions inside the quick tool.

Per-source WAV information remains visible, including filename, sample rate, mono/stereo layout, bit depth, and PCM/float encoding.

Currently supported input WAV sample encodings:

- PCM integer: 8, 16, 24, and 32-bit
- IEEE float: 32 and 64-bit
- WAVE_FORMAT_EXTENSIBLE when its sub-format is PCM or IEEE float

Export remains standard 16-bit PCM WAV, preserving the project sample rate and mono/stereo layout. Playback and export are still intentionally limited to one non-overlapping track at one native sample rate. Mixing, resampling, channel conversion, fades, additional codecs, and RF64 come later.

## First useful target

Open audio → waveform → select → trim / split / move / amplify → undo / redo → export.

Brick #8 reached that end-to-end target. Brick #9 extended it to multiple input sources, Brick #9.1 sealed the first multisource device-test regressions, and Brick #10 proves a quick tool can be only a friendly front door into the exact same machinery.

Quick Trim and Quick Amplify should follow the same rule.

## Build

Open the repository in Android Studio, or use Gradle 9.5.0 with JDK 17:

```bash
gradle testDebugUnitTest assembleDebug
```

A Gradle wrapper will be committed once generated from a normal Gradle installation; until then CI installs the pinned Gradle version explicitly.

## Licence

GPL-3.0-or-later. See `LICENSE`.
