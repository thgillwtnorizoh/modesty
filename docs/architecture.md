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

This avoids quietly modifying source files and makes mixed-rate projects possible later.

## Project metadata

`AudioProject` contains source descriptions and tracks. `AudioTrack` contains clips. `AudioClip` points at a range of an immutable source plus timeline/edit metadata such as gain and fades.

The source audio itself is not stored inside the clip object.

## Edits and history

Editing operations are pure metadata transformations where possible. Current operations include move, trim, split, range split/delete, and clip gain.

`ProjectEditor` owns undo/redo history and transaction support. Continuous interactions can be consolidated into one history entry by starting a transaction, applying preview states, and committing once. Brick #6's current Android clip move previews entirely in presentation state and commits one `MoveClip` on finger release, so one drag is also one undo item without polluting history with every motion event.

`MoveClip` now checks Brick #6 movement constraints. `clipMoveBounds()` derives a legal horizontal interval from the neighbouring clips on the same track. Until overlapping playback/mixing exists:

- clips cannot move before frame zero
- clips cannot overlap neighbours
- clips cannot cross/reorder past neighbours
- a move into an existing silence gap is legal

This is a policy boundary, not a permanent limitation of the project model.

## DSP

DSP operates on interleaved floating-point PCM and has no Android/UI dependency. `GainProcessor` is intentionally tiny and acts as the reference for future processors.

## Audio ingestion

Format decoders implement the Android-free `AudioDecoder` contract. `WavDecoder` receives an `InputStream` factory rather than a `File` or Android `Uri`. This keeps RIFF/WAVE parsing in the core and allows seeking by reopening and skipping even when a document provider does not expose a directly seekable descriptor.

The WAV decoder currently supports PCM integer 8/16/24/32-bit samples, IEEE float 32/64-bit samples, and WAVE_FORMAT_EXTENSIBLE when its sub-format resolves to either of those encodings.

## Waveforms

`WaveformCache` exposes visible-range, fixed-resolution buckets rather than a bitmap or Android drawing primitive.

The waveform pyramid records min, max, RMS, and represented frame count for each channel over 256-frame base buckets. Coarser levels combine neighbouring buckets until a one-bucket overview remains. Rendering selects the level closest to the requested horizontal resolution instead of rescanning source audio.

The cache represents immutable full sources. Timeline clips merely request their own source ranges, so trim/split/delete/move never rebuild waveform analysis.

Brick #5 made `WaveformView` timeline-aware and able to draw several source-backed clip segments plus empty gaps. Brick #6 adds a small move handle at the top of every visible clip. Gesture ownership is now explicit:

- tap the waveform: seek
- horizontal drag in waveform body: range selection
- horizontal drag on the `C#` clip header: clip-move preview
- vertical drag: parent page scrolling

The move preview is visual until release. The final timeline start is clamped to the core-provided legal move bounds, then committed through `MoveClip`.

## Playback

The current playback backend uses `TimelinePlaybackPlan` plus `AndroidTimelinePlaybackEngine`.

It supports exactly one track containing multiple non-overlapping clips and silence gaps. Gaps are written as real zero samples through the same `AudioTrack`, so the hardware playback head remains the timing authority instead of the UI pretending time passed.

All clips currently need the same native sample rate and channel layout because the realtime resampler/mixer has not been introduced yet. Overlapping clips are deliberately rejected by the playback plan.

After every committed edit or undo/redo, the Android shell reloads the playback plan from `ProjectEditor.project`, so playback and waveform presentation receive the same clip positions.

## Android shell

Android owns document selection, touch presentation, and the current reference playback backend. WAV decoding and waveform analysis run on a worker thread.

The current data flow is:

```text
Document Uri
   |
   +--> WavDecoder --> waveform pyramid/cache
   |
   +--> AudioSource --> AudioProject --> ProjectEditor
                                      |          |
                                      |          +--> Trim / Split / Delete / Move / Undo / Redo
                                      |
                                      +--> timeline playback plan --> AudioTrack
                                      |
                                      +--> timeline clips --> WaveformView
```

Clip source ranges and timeline starts are saved through Android instance state so configuration recreation preserves the current arrangement. Undo history itself is intentionally not persisted yet.

## I/O direction

Encoder APIs remain contracts. Likely later implementation direction:

- Oboe/AAudio for the mature realtime backend
- native resampling/mixing engine
- format-specific decoders where sensible
- FFmpeg-backed compatibility layer for broad import/export

These remain implementation decisions, not project-model dependencies.
