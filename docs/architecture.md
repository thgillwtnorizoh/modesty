# Modesty architecture

## Core rule

Quick tools and the full editor are different user interfaces over the same editing engine.

```text
Quick Trim -----\
Quick Amplify ---+--> Editor operations --> Project model
Quick Join ------+                         /      |      \
Full Editor -----/                       DSP   Waveform  Playback
```

## Time domains

Modesty deliberately keeps source time and timeline time separate.

- A source range is measured in frames at that source's native sample rate.
- Timeline positions are measured in frames at the project's timeline sample rate.
- Importing a source does not destructively resample it.
- Playback/export implementations are responsible for converting source audio into the timeline rate when required.

This avoids quietly modifying source files and makes mixed-rate projects possible.

## Project metadata

`AudioProject` contains source descriptions and tracks. `AudioTrack` contains clips. `AudioClip` points at a range of an immutable source plus timeline/edit metadata such as gain and fades.

The source audio itself is not stored inside the clip object.

## Edits

Editing operations are pure metadata transformations where possible. The first operations are move, trim, split, and clip gain.

Continuous touch interactions use `ProjectEditor` transactions:

1. begin transaction
2. apply as many preview updates as the gesture produces
3. commit once when the finger/stylus is released
4. or roll back if the gesture is cancelled

A completed gesture therefore becomes one undo entry.

## DSP

DSP operates on interleaved floating-point PCM and has no Android/UI dependency. `GainProcessor` is intentionally tiny and acts as the reference for future processors.

## Audio ingestion

Format decoders implement the Android-free `AudioDecoder` contract. Brick #2 adds `WavDecoder`, which receives an `InputStream` factory rather than a `File` or Android `Uri`. This keeps RIFF/WAVE parsing in the core and allows seeking by reopening and skipping even when a document provider does not expose a directly seekable descriptor.

The WAV decoder currently supports PCM integer 8/16/24/32-bit samples, IEEE float 32/64-bit samples, and WAVE_FORMAT_EXTENSIBLE when its sub-format resolves to either of those encodings.

## Waveforms

`WaveformCache` exposes visible-range, fixed-resolution buckets rather than a bitmap or Android drawing primitive.

Brick #2 implements a streaming waveform pyramid. The base level records min, max, RMS, and represented frame count for each channel over 256-frame buckets. Each coarser level combines neighbouring buckets until a one-bucket overview remains. Rendering selects the level closest to the requested horizontal resolution instead of rescanning source audio.

The current cache is in-memory. A later brick can persist or tile waveform data without changing the UI-facing cache contract.

## Android shell

Android owns document selection and presentation only. WAV decoding and waveform analysis run on a worker thread; the activity receives completed metadata and a cache to draw. The custom `WaveformView` reads waveform buckets rather than raw PCM.

## I/O and playback

Encoder and playback APIs remain contracts at Brick #2. Their implementations are intentionally deferred so platform/library choices do not leak into the project model.

Likely later implementation direction:

- Oboe/AAudio for realtime playback
- native resampling/mixing engine
- format-specific decoders where sensible
- FFmpeg-backed compatibility layer for broad import/export

These are implementation decisions, not project-model dependencies.
