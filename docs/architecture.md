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

`AudioProject` exposes conversions in both directions between source frames and project frames. Brick #5 needs the reverse conversion because timeline selections may cut a source-backed clip at arbitrary timeline boundaries.

## Project metadata

`AudioProject` contains source descriptions and tracks. `AudioTrack` contains clips. `AudioClip` points at a range of an immutable source plus timeline/edit metadata such as gain and fades.

The source audio itself is not stored inside the clip object. Splitting a clip creates more clip descriptors referencing the same source. Deleting a range removes or shortens those descriptors; it never rewrites the source.

## Edits

Editing operations are pure metadata transformations where possible. The current operations include move, trim, split, clip gain, split-at-timeline-selection, and delete-timeline-range.

Continuous touch interactions use `ProjectEditor` transactions:

1. begin transaction
2. apply as many preview updates as the gesture produces
3. commit once when the finger/stylus is released
4. or roll back if the gesture is cancelled

A completed gesture therefore becomes one undo entry.

Brick #4 connected `TrimClip` to the Android shell. Brick #5 adds two timeline operations:

- `SplitTimelineRange` splits clips at the start and end boundaries of a selection. Boundaries that already land on clip edges or silence are no-ops.
- `DeleteTimelineRange` removes audio under a timeline selection while preserving every surviving clip's timeline position. The removed interval therefore becomes silence. This is deliberately non-ripple delete.

`ProjectEditor` already stores complete before/after project snapshots, so undo and redo work across split and delete without rebuilding source audio.

## DSP

DSP operates on interleaved floating-point PCM and has no Android/UI dependency. `GainProcessor` is intentionally tiny and acts as the reference for future processors.

## Audio ingestion

Format decoders implement the Android-free `AudioDecoder` contract. `WavDecoder` receives an `InputStream` factory rather than a `File` or Android `Uri`. This keeps RIFF/WAVE parsing in the core and allows seeking by reopening and skipping even when a document provider does not expose a directly seekable descriptor.

The WAV decoder currently supports PCM integer 8/16/24/32-bit samples, IEEE float 32/64-bit samples, and WAVE_FORMAT_EXTENSIBLE when its sub-format resolves to either of those encodings.

## Waveforms

`WaveformCache` exposes visible-range, fixed-resolution buckets rather than a bitmap or Android drawing primitive.

The streaming waveform pyramid records min, max, RMS, and represented frame count for each channel over 256-frame base buckets. Coarser levels combine neighbouring buckets until a one-bucket overview remains. Rendering selects the level closest to the requested horizontal resolution instead of rescanning source audio.

The cache still represents immutable sources. Brick #5 changes only presentation: `WaveformView` now receives a list of timeline clip descriptors. Each descriptor maps a source range into a timeline range. The view reads only the source buckets needed for each visible clip and leaves uncovered timeline intervals blank.

This is the first real timeline view:

- tap maps x-position to timeline seek
- horizontal drag maps x-position to a timeline selection
- clip boundaries are visible
- deleted intervals remain visible as gaps
- vertical page scrolling and horizontal selection continue to use the Brick #4.1 gesture arbiter

## Playback

Brick #3 proved one-clip playback with a hardware-derived playhead. Brick #5 generalizes the plan into `TimelinePlaybackPlan`.

`TimelinePlaybackPlan` currently accepts exactly one track containing one or more non-overlapping clips. It validates that all clips can share one output format until the mixer/resampler arrives. Gaps between clips are legitimate timeline regions.

`AndroidTimelinePlaybackEngine` consumes the plan using one Android `AudioTrack`:

```text
clip PCM ----\
              +--> one continuous AudioTrack stream --> hardware playhead
zeroed gap --/
```

When playback enters a deleted region, the engine writes zero samples for exactly that gap duration. Because clips and silence travel through the same `AudioTrack`, `playbackHeadPosition` remains the authoritative clock across edits rather than switching to a UI timer.

Current playback limits are intentional:

- exactly one track
- no overlapping clips
- mono or stereo
- project rate equals source rate
- no clip gain or fades in the realtime path yet

## Android shell

Android owns document selection and presentation. WAV decoding and waveform analysis run on a worker thread.

The activity currently wires together:

```text
Document Uri
   |
   +--> WavDecoder --> immutable waveform pyramid/cache
   |
   +--> AudioSource --> AudioProject --> ProjectEditor
                                      |       |       |
                                      |       |       +--> Undo / Redo
                                      |       +----------> Trim / Split / Delete
                                      |
                                      +--> TimelinePlaybackPlan --> AudioTrack
                                      |
                                      +--> timeline clip descriptors --> WaveformView
```

For configuration recreation, the activity saves the current clip list as IDs, source ranges, and timeline starts. The underlying source is reopened and the project metadata is reconstructed. Edit history itself is not yet persisted across process/activity recreation.

## I/O direction

Encoder APIs remain contracts. Likely later implementation direction:

- Oboe/AAudio for the mature realtime backend
- native resampling/mixing engine
- format-specific decoders where sensible
- FFmpeg-backed compatibility layer for broad import/export

These are implementation decisions, not project-model dependencies.
