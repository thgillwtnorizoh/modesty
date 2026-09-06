package io.github.thgillwtnorizoh.modesty.core.editing

import io.github.thgillwtnorizoh.modesty.core.model.AudioClip
import io.github.thgillwtnorizoh.modesty.core.model.AudioProject
import io.github.thgillwtnorizoh.modesty.core.model.SourceRange

/**
 * Splits clips at the two timeline boundaries of a selection without moving any audio.
 * Boundaries that already fall on a clip edge or in silence are harmless no-ops.
 */
data class SplitTimelineRange(
    val trackId: String,
    val startTimelineFrame: Long,
    val endTimelineFrameExclusive: Long,
    val rightClipIdAtStart: String,
    val rightClipIdAtEnd: String,
) : EditOperation {
    override val description: String = "Split selection"

    override fun applyTo(project: AudioProject): AudioProject {
        require(startTimelineFrame >= 0)
        require(endTimelineFrameExclusive > startTimelineFrame)
        require(rightClipIdAtStart.isNotBlank())
        require(rightClipIdAtEnd.isNotBlank())
        require(rightClipIdAtStart != rightClipIdAtEnd)

        var result = project.splitAtTimelineBoundary(trackId, startTimelineFrame, rightClipIdAtStart)
        result = result.splitAtTimelineBoundary(trackId, endTimelineFrameExclusive, rightClipIdAtEnd)
        return result.validate()
    }
}

/**
 * Removes audio under a timeline selection while preserving the timeline positions of everything
 * else. The result therefore contains silence where audio was deleted. Ripple delete is a separate
 * future operation.
 */
data class DeleteTimelineRange(
    val trackId: String,
    val startTimelineFrame: Long,
    val endTimelineFrameExclusive: Long,
    val rightClipId: String,
) : EditOperation {
    override val description: String = "Delete range"

    override fun applyTo(project: AudioProject): AudioProject {
        require(startTimelineFrame >= 0)
        require(endTimelineFrameExclusive > startTimelineFrame)
        require(rightClipId.isNotBlank())

        val trackIndex = project.tracks.indexOfFirst { it.id == trackId }
        require(trackIndex >= 0) { "Unknown track: $trackId" }
        val track = project.tracks[trackIndex]

        var middleSplitUsed = false
        val newClips = mutableListOf<AudioClip>()

        for (clip in track.clips) {
            val clipStart = clip.timelineStartFrame
            val clipEnd = project.clipTimelineEndFrameExclusive(clip)
            val overlapStart = maxOf(startTimelineFrame, clipStart)
            val overlapEnd = minOf(endTimelineFrameExclusive, clipEnd)

            if (overlapStart >= overlapEnd) {
                newClips += clip
                continue
            }

            val removesWholeClip = overlapStart <= clipStart && overlapEnd >= clipEnd
            if (removesWholeClip) continue

            val removesPrefix = overlapStart <= clipStart
            val removesSuffix = overlapEnd >= clipEnd

            when {
                removesPrefix -> {
                    val removedProjectFrames = overlapEnd - clipStart
                    val removedSourceFrames = project
                        .projectFramesToSourceFrames(clip.sourceId, removedProjectFrames)
                        .coerceIn(0L, clip.sourceRange.lengthFrames)
                    val newStart = clip.sourceRange.startFrame + removedSourceFrames
                    if (newStart < clip.sourceRange.endFrameExclusive) {
                        val newRange = SourceRange(newStart, clip.sourceRange.endFrameExclusive)
                        newClips += clip.copy(
                            sourceRange = newRange,
                            timelineStartFrame = overlapEnd,
                            fadeInSourceFrames = 0,
                            fadeOutSourceFrames = clip.fadeOutSourceFrames.coerceAtMost(newRange.lengthFrames),
                        )
                    }
                }

                removesSuffix -> {
                    val keptProjectFrames = overlapStart - clipStart
                    val keptSourceFrames = project
                        .projectFramesToSourceFrames(clip.sourceId, keptProjectFrames)
                        .coerceIn(0L, clip.sourceRange.lengthFrames)
                    val newEnd = clip.sourceRange.startFrame + keptSourceFrames
                    if (newEnd > clip.sourceRange.startFrame) {
                        val newRange = SourceRange(clip.sourceRange.startFrame, newEnd)
                        newClips += clip.copy(
                            sourceRange = newRange,
                            fadeInSourceFrames = clip.fadeInSourceFrames.coerceAtMost(newRange.lengthFrames),
                            fadeOutSourceFrames = 0,
                        )
                    }
                }

                else -> {
                    require(!middleSplitUsed) {
                        "Delete range would require more than one middle split; overlapping clips are not supported in Brick 5"
                    }
                    require(project.tracks.none { candidate -> candidate.clips.any { it.id == rightClipId } }) {
                        "Clip id already exists: $rightClipId"
                    }
                    middleSplitUsed = true

                    val leftProjectFrames = overlapStart - clipStart
                    val rightProjectOffset = overlapEnd - clipStart
                    val leftSourceFrames = project.projectFramesToSourceFrames(clip.sourceId, leftProjectFrames)
                    val rightSourceOffset = project.projectFramesToSourceFrames(clip.sourceId, rightProjectOffset)
                    val leftEnd = (clip.sourceRange.startFrame + leftSourceFrames)
                        .coerceIn(clip.sourceRange.startFrame, clip.sourceRange.endFrameExclusive)
                    val rightStart = (clip.sourceRange.startFrame + rightSourceOffset)
                        .coerceIn(clip.sourceRange.startFrame, clip.sourceRange.endFrameExclusive)

                    if (leftEnd > clip.sourceRange.startFrame) {
                        val leftRange = SourceRange(clip.sourceRange.startFrame, leftEnd)
                        newClips += clip.copy(
                            sourceRange = leftRange,
                            fadeInSourceFrames = clip.fadeInSourceFrames.coerceAtMost(leftRange.lengthFrames),
                            fadeOutSourceFrames = 0,
                        )
                    }
                    if (rightStart < clip.sourceRange.endFrameExclusive) {
                        val rightRange = SourceRange(rightStart, clip.sourceRange.endFrameExclusive)
                        newClips += clip.copy(
                            id = rightClipId,
                            sourceRange = rightRange,
                            timelineStartFrame = overlapEnd,
                            fadeInSourceFrames = 0,
                            fadeOutSourceFrames = clip.fadeOutSourceFrames.coerceAtMost(rightRange.lengthFrames),
                        )
                    }
                }
            }
        }

        val newTracks = project.tracks.toMutableList().apply {
            this[trackIndex] = track.copy(clips = newClips.sortedBy { it.timelineStartFrame })
        }
        return project.copy(tracks = newTracks).validate()
    }
}

private fun AudioProject.splitAtTimelineBoundary(
    trackId: String,
    timelineFrame: Long,
    rightClipId: String,
): AudioProject {
    val track = tracks.firstOrNull { it.id == trackId } ?: error("Unknown track: $trackId")
    val clip = track.clips.firstOrNull { candidate ->
        timelineFrame > candidate.timelineStartFrame &&
            timelineFrame < clipTimelineEndFrameExclusive(candidate)
    } ?: return this

    val projectOffset = timelineFrame - clip.timelineStartFrame
    val sourceOffset = projectFramesToSourceFrames(clip.sourceId, projectOffset)
    val splitSourceFrame = (clip.sourceRange.startFrame + sourceOffset)
        .coerceIn(clip.sourceRange.startFrame, clip.sourceRange.endFrameExclusive)

    if (splitSourceFrame <= clip.sourceRange.startFrame || splitSourceFrame >= clip.sourceRange.endFrameExclusive) {
        return this
    }

    return SplitClip(
        trackId = trackId,
        clipId = clip.id,
        splitSourceFrame = splitSourceFrame,
        rightClipId = rightClipId,
    ).applyTo(this)
}
