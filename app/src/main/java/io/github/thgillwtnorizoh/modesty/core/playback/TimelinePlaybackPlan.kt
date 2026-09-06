package io.github.thgillwtnorizoh.modesty.core.playback

import io.github.thgillwtnorizoh.modesty.core.model.AudioClip
import io.github.thgillwtnorizoh.modesty.core.model.AudioProject
import io.github.thgillwtnorizoh.modesty.core.model.AudioSource
import io.github.thgillwtnorizoh.modesty.core.model.SampleRate

/** One non-overlapping piece of audio on Brick #5's single playback track. */
data class TimelinePlaybackSegment(
    val source: AudioSource,
    val clip: AudioClip,
    val timelineEndFrameExclusive: Long,
) {
    val timelineStartFrame: Long
        get() = clip.timelineStartFrame

    fun contains(frame: Long): Boolean = frame >= timelineStartFrame && frame < timelineEndFrameExclusive

    fun sourceFrameForTimeline(project: AudioProject, frame: Long): Long {
        require(frame in timelineStartFrame..timelineEndFrameExclusive)
        val timelineOffset = frame - timelineStartFrame
        val sourceOffset = project.projectFramesToSourceFrames(source.id, timelineOffset)
        return (clip.sourceRange.startFrame + sourceOffset)
            .coerceIn(clip.sourceRange.startFrame, clip.sourceRange.endFrameExclusive)
    }
}

/**
 * Brick #5 playback plan: exactly one track, but that track may contain several non-overlapping
 * clips and silence gaps. All clips must currently share one output format because the realtime
 * resampler/mixer has not been introduced yet.
 */
data class TimelinePlaybackPlan(
    val project: AudioProject,
    val sampleRate: SampleRate,
    val channelCount: Int,
    val segments: List<TimelinePlaybackSegment>,
) {
    init {
        require(segments.isNotEmpty())
    }

    val timelineStartFrame: Long = segments.first().timelineStartFrame
    val timelineEndFrameExclusive: Long = segments.maxOf { it.timelineEndFrameExclusive }

    fun clampTimelineFrame(frame: Long): Long =
        frame.coerceIn(timelineStartFrame, timelineEndFrameExclusive)

    fun segmentAt(frame: Long): TimelinePlaybackSegment? =
        segments.firstOrNull { it.contains(frame) }

    fun nextSegmentStartAfter(frame: Long): Long? =
        segments.firstOrNull { it.timelineStartFrame > frame }?.timelineStartFrame

    companion object {
        fun from(project: AudioProject): TimelinePlaybackPlan {
            require(project.tracks.size == 1) { "Brick 5 playback supports exactly one track" }
            val clips = project.tracks.single().clips.sortedBy { it.timelineStartFrame }
            require(clips.isNotEmpty()) { "Brick 5 playback needs at least one clip" }

            val segments = clips.map { clip ->
                val source = project.sources[clip.sourceId]
                    ?: error("Clip ${clip.id} references missing source ${clip.sourceId}")
                require(project.timelineRate == source.sampleRate) {
                    "Brick 5 has no resampler yet: project and source rates must match"
                }
                require(source.channelCount in 1..2) {
                    "Brick 5 playback currently supports mono or stereo sources"
                }
                require(clip.gain == 1f) { "Brick 5 playback does not apply clip gain yet" }
                require(clip.fadeInSourceFrames == 0L && clip.fadeOutSourceFrames == 0L) {
                    "Brick 5 playback does not apply fades yet"
                }
                TimelinePlaybackSegment(
                    source = source,
                    clip = clip,
                    timelineEndFrameExclusive = project.clipTimelineEndFrameExclusive(clip),
                )
            }

            segments.zipWithNext().forEach { (left, right) ->
                require(left.timelineEndFrameExclusive <= right.timelineStartFrame) {
                    "Brick 5 playback does not mix overlapping clips yet"
                }
            }

            val firstSource = segments.first().source
            segments.drop(1).forEach { segment ->
                require(segment.source.sampleRate == firstSource.sampleRate) {
                    "Brick 5 playback needs one sample rate until the resampler exists"
                }
                require(segment.source.channelCount == firstSource.channelCount) {
                    "Brick 5 playback needs one channel layout until the mixer exists"
                }
            }

            return TimelinePlaybackPlan(
                project = project,
                sampleRate = firstSource.sampleRate,
                channelCount = firstSource.channelCount,
                segments = segments,
            )
        }
    }
}
