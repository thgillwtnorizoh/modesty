package io.github.thgillwtnorizoh.modesty.core.playback

import io.github.thgillwtnorizoh.modesty.core.model.AudioClip
import io.github.thgillwtnorizoh.modesty.core.model.AudioProject
import io.github.thgillwtnorizoh.modesty.core.model.AudioSource

/**
 * Brick #3's deliberately small playback plan: exactly one clip from exactly one track.
 *
 * It proves the project/clip -> decoder time mapping before Modesty grows a real mixer and
 * resampler. The Android playback backend consumes this plan without knowing about UI state.
 */
data class SingleClipPlaybackPlan(
    val source: AudioSource,
    val clip: AudioClip,
) {
    val timelineStartFrame: Long
        get() = clip.timelineStartFrame

    val timelineEndFrameExclusive: Long
        get() = timelineStartFrame + clip.sourceRange.lengthFrames

    fun clampTimelineFrame(frame: Long): Long =
        frame.coerceIn(timelineStartFrame, timelineEndFrameExclusive)

    fun sourceFrameForTimeline(frame: Long): Long {
        require(frame in timelineStartFrame..timelineEndFrameExclusive) {
            "Timeline frame $frame is outside the playable clip"
        }
        return clip.sourceRange.startFrame + (frame - timelineStartFrame)
    }

    companion object {
        fun from(project: AudioProject): SingleClipPlaybackPlan {
            require(project.tracks.size == 1) { "Brick 3 playback supports exactly one track" }
            val clip = project.tracks.single().clips.singleOrNull()
                ?: error("Brick 3 playback supports exactly one clip")
            val source = project.sources[clip.sourceId]
                ?: error("Clip ${clip.id} references missing source ${clip.sourceId}")

            require(project.timelineRate == source.sampleRate) {
                "Brick 3 has no resampler yet: project and source rates must match"
            }
            require(clip.gain == 1f) { "Brick 3 playback does not apply clip gain yet" }
            require(clip.fadeInSourceFrames == 0L && clip.fadeOutSourceFrames == 0L) {
                "Brick 3 playback does not apply fades yet"
            }

            return SingleClipPlaybackPlan(source, clip)
        }
    }
}
