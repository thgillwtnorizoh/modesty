package io.github.thgillwtnorizoh.modesty.core.editing

import io.github.thgillwtnorizoh.modesty.core.model.AudioClip
import io.github.thgillwtnorizoh.modesty.core.model.AudioProject
import io.github.thgillwtnorizoh.modesty.core.model.SourceRange

sealed interface EditOperation {
    val description: String
    fun applyTo(project: AudioProject): AudioProject
}

data class MoveClip(
    val trackId: String,
    val clipId: String,
    val newTimelineStartFrame: Long,
) : EditOperation {
    override val description: String = "Move clip"

    override fun applyTo(project: AudioProject): AudioProject {
        require(newTimelineStartFrame >= 0)
        return project.updateClip(trackId, clipId) { it.copy(timelineStartFrame = newTimelineStartFrame) }
    }
}

data class SetClipGain(
    val trackId: String,
    val clipId: String,
    val gain: Float,
) : EditOperation {
    override val description: String = "Change clip gain"

    override fun applyTo(project: AudioProject): AudioProject =
        project.updateClip(trackId, clipId) { it.copy(gain = gain) }
}

data class TrimClip(
    val trackId: String,
    val clipId: String,
    val newSourceStartFrame: Long,
    val newSourceEndFrameExclusive: Long,
) : EditOperation {
    override val description: String = "Trim clip"

    override fun applyTo(project: AudioProject): AudioProject = project.updateClip(trackId, clipId) { clip ->
        require(newSourceStartFrame >= clip.sourceRange.startFrame) { "Trim cannot reveal audio before the current clip boundary" }
        require(newSourceEndFrameExclusive <= clip.sourceRange.endFrameExclusive) { "Trim cannot reveal audio after the current clip boundary" }

        val newRange = SourceRange(newSourceStartFrame, newSourceEndFrameExclusive)
        val trimmedFromLeft = newRange.startFrame - clip.sourceRange.startFrame
        val timelineShift = project.sourceFramesToProjectFrames(clip.sourceId, trimmedFromLeft)

        clip.copy(
            sourceRange = newRange,
            timelineStartFrame = clip.timelineStartFrame + timelineShift,
            fadeInSourceFrames = clip.fadeInSourceFrames.coerceAtMost(newRange.lengthFrames),
            fadeOutSourceFrames = clip.fadeOutSourceFrames.coerceAtMost(
                newRange.lengthFrames - clip.fadeInSourceFrames.coerceAtMost(newRange.lengthFrames),
            ),
        )
    }
}

data class SplitClip(
    val trackId: String,
    val clipId: String,
    val splitSourceFrame: Long,
    val rightClipId: String,
) : EditOperation {
    override val description: String = "Split clip"

    override fun applyTo(project: AudioProject): AudioProject {
        require(rightClipId.isNotBlank())
        require(project.tracks.none { track -> track.clips.any { it.id == rightClipId } }) {
            "Clip id already exists: $rightClipId"
        }

        val trackIndex = project.tracks.indexOfFirst { it.id == trackId }
        require(trackIndex >= 0) { "Unknown track: $trackId" }
        val track = project.tracks[trackIndex]
        val clipIndex = track.clips.indexOfFirst { it.id == clipId }
        require(clipIndex >= 0) { "Unknown clip: $clipId" }
        val clip = track.clips[clipIndex]

        require(splitSourceFrame > clip.sourceRange.startFrame && splitSourceFrame < clip.sourceRange.endFrameExclusive) {
            "Split point must be inside the clip"
        }

        val leftRange = SourceRange(clip.sourceRange.startFrame, splitSourceFrame)
        val rightRange = SourceRange(splitSourceFrame, clip.sourceRange.endFrameExclusive)
        val rightStart = clip.timelineStartFrame + project.sourceFramesToProjectFrames(clip.sourceId, leftRange.lengthFrames)

        val left = clip.copy(
            sourceRange = leftRange,
            fadeOutSourceFrames = 0,
            fadeInSourceFrames = clip.fadeInSourceFrames.coerceAtMost(leftRange.lengthFrames),
        )
        val right = clip.copy(
            id = rightClipId,
            sourceRange = rightRange,
            timelineStartFrame = rightStart,
            fadeInSourceFrames = 0,
            fadeOutSourceFrames = clip.fadeOutSourceFrames.coerceAtMost(rightRange.lengthFrames),
        )

        val newClips = track.clips.toMutableList().apply {
            this[clipIndex] = left
            add(clipIndex + 1, right)
        }
        val newTracks = project.tracks.toMutableList().apply {
            this[trackIndex] = track.copy(clips = newClips)
        }
        return project.copy(tracks = newTracks).validate()
    }
}

private fun AudioProject.updateClip(
    trackId: String,
    clipId: String,
    transform: (AudioClip) -> AudioClip,
): AudioProject {
    val trackIndex = tracks.indexOfFirst { it.id == trackId }
    require(trackIndex >= 0) { "Unknown track: $trackId" }
    val track = tracks[trackIndex]
    val clipIndex = track.clips.indexOfFirst { it.id == clipId }
    require(clipIndex >= 0) { "Unknown clip: $clipId" }

    val newClips = track.clips.toMutableList().apply {
        this[clipIndex] = transform(this[clipIndex])
    }
    val newTracks = tracks.toMutableList().apply {
        this[trackIndex] = track.copy(clips = newClips)
    }
    return copy(tracks = newTracks).validate()
}
