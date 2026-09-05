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

## Waveforms

`WaveformCache` exposes visible-range, fixed-resolution buckets rather than a bitmap or Android drawing primitive. A later implementation can build a multi-resolution min/max/RMS cache and render it with whichever UI technology proves best.

## I/O and playback

Decoder, encoder, and playback APIs are contracts only in the first brick. Their implementations are intentionally deferred so platform/library choices do not leak into the project model.

Likely later implementation direction:

- Oboe/AAudio for realtime playback
- native resampling/mixing engine
- format-specific decoders where sensible
- FFmpeg-backed compatibility layer for broad import/export

These are implementation decisions, not project-model dependencies.
