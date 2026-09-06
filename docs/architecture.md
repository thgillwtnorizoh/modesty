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

Brick #4 connects the existing `TrimClip` operation to the Android shell. A trim changes only `AudioClip.sourceRange` and, when trimming from the left, advances the clip's timeline start by the matching duration. The source WAV remains unchanged. Undo swaps the project metadata back to the previous state.

## DSP

DSP operates on interleaved floating-point PCM and has no Android/UI dependency. `GainProcessor` is intentionally tiny and acts as the reference for future processors.

## Audio ingestion

Format decoders implement the Android-free `AudioDecoder` contract. Brick #2 adds `WavDecoder`, which receives an `InputStream` factory rather than a `File` or Android `Uri`. This keeps RIFF/WAVE parsing in the core and allows seeking by reopening and skipping even when a document provider does not expose a directly seekable descriptor.

The WAV decoder currently supports PCM integer 8/16/24/32-bit samples, IEEE float 32/64-bit samples, and WAVE_FORMAT_EXTENSIBLE when its sub-format resolves to either of those encodings.

## Waveforms

`WaveformCache` exposes visible-range, fixed-resolution buckets rather than a bitmap or Android drawing primitive.

Brick #2 implements a streaming waveform pyramid. The base level records min, max, RMS, and represented frame count for each channel over 256-frame buckets. Each coarser level combines neighbouring buckets until a one-bucket overview remains. Rendering selects the level closest to the requested horizontal resolution instead of rescanning source audio.

The current cache is in-memory. A later brick can persist or tile waveform data without changing the UI-facing cache contract.

Brick #4 makes the waveform view clip-aware. The cache still represents the immutable full source, while the view requests only the clip's current source range. This means trimming does not rebuild waveform analysis. The view keeps source and timeline windows separate so a later resampler can change their ratio without redesigning touch mapping.

Touch behaviour at Brick #4 is intentionally small:

- tap maps the visible timeline window to a seek request
- horizontal drag maps the visible source window to a selection
- the selection is presentation state, not an edit
- pressing Trim converts that selection into one `TrimClip` edit

## Playback

Brick #3 adds the first real playback backend. `SingleClipPlaybackPlan` maps the current project/track/clip metadata into one playable source range. `AndroidSingleClipPlaybackEngine` consumes that plan through `AudioDecoder`, streams float PCM to Android `AudioTrack`, and derives the visible playhead from `AudioTrack.playbackHeadPosition` rather than a UI timer.

The current playback foundation deliberately supports exactly one clip on one track, mono/stereo output, and a project rate equal to the source rate. There is no mixer or resampler yet.

After Brick #4, every trim or undo reloads the playback plan from the current `ProjectEditor.project`, so playback and waveform presentation receive the same clip boundaries.

## Android shell

Android owns document selection and presentation only. WAV decoding and waveform analysis run on a worker thread; the activity receives completed metadata and a cache to draw.

The activity currently wires together:

```text
Document Uri
   |
   +--> WavDecoder --> waveform pyramid/cache
   |
   +--> AudioSource --> AudioProject --> ProjectEditor
                                      |          |
                                      |          +--> Trim / Undo
                                      |
                                      +--> playback plan --> AudioTrack
                                      |
                                      +--> clip window --> WaveformView
```

The current clip source range is saved through Android instance state so a simple configuration recreation does not silently restore the full source after a trim.

## I/O direction

Encoder APIs remain contracts. Likely later implementation direction:

- Oboe/AAudio for the mature realtime backend
- native resampling/mixing engine
- format-specific decoders where sensible
- FFmpeg-backed compatibility layer for broad import/export

These are implementation decisions, not project-model dependencies.
