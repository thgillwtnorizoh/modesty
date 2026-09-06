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

Brick #3 adds a playhead to the same `WaveformView`. The view knows only the current frame and maps a tap back to a requested source/timeline frame; it does not own playback.

## Playback

Brick #3 activates the `PlaybackEngine` boundary with `AndroidSingleClipPlaybackEngine`.

The engine deliberately supports only one clip on one track, mono or stereo output, with the project timeline rate equal to the source rate. `SingleClipPlaybackPlan` performs the project/clip -> source-frame mapping in Android-free code and is unit tested. This limitation is intentional: it proves clocking, pause/resume, seeking, end-of-file behaviour, and decoder/output integration before a mixer or resampler is introduced.

The reference backend streams float PCM from `AudioDecoder` into Android `AudioTrack`. The UI polls the engine's hardware-derived playhead and draws it over the waveform. Tapping the waveform seeks the engine; tapping while playing resumes from the requested frame.

`AudioTrack` is not a permanent architectural dependency. Once the timeline behaviour is proven on devices, a later backend can use Oboe/AAudio without changing the project model or editor-facing `PlaybackEngine` contract.

Current deliberate Brick #3 exclusions:

- no mixed sample-rate playback / resampler
- no multi-clip or multi-track mixer
- no fades or clip gain in the playback path
- no background playback policy
- no audio-focus layer yet

## Android shell

Android owns document selection and presentation. WAV decoding and waveform analysis run on a worker thread. Playback owns a separate worker and never performs file decoding on the UI thread.

The activity pauses playback when it leaves the foreground and releases the playback engine when destroyed.

## I/O direction

Encoder APIs remain contracts. Later implementation direction remains:

- Oboe/AAudio for the mature realtime backend
- native resampling/mixing engine
- format-specific decoders where sensible
- FFmpeg-backed compatibility layer for broad import/export

These remain implementation choices behind stable project/editor boundaries.
