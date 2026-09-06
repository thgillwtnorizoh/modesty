package io.github.thgillwtnorizoh.modesty.core.editing

import io.github.thgillwtnorizoh.modesty.core.model.AudioProject

/**
 * Horizontal movement limits for one clip while Brick #6 still forbids overlap and reordering.
 * The current neighbours become hard walls. A later mixer/arranger can replace this policy
 * without changing MoveClip itself or ProjectEditor history semantics.
 */
data class ClipMoveBounds(
    val minimumStartFrame: Long,
    val maximumStartFrame: Long,
) {
    init {
        require(minimumStartFrame >= 0)
        require(maximumStartFrame >= minimumStartFrame)
    }

    fun clamp(startFrame: Long): Long = startFrame.coerceIn(minimumStartFrame, maximumStartFrame)
}

fun AudioProject.clipMoveBounds(trackId: String, clipId: String): ClipMoveBounds {
    val track = tracks.firstOrNull { it.id == trackId } ?: error("Unknown track: $trackId")
    val ordered = track.clips.sortedBy { it.timelineStartFrame }
    val index = ordered.indexOfFirst { it.id == clipId }
    require(index >= 0) { "Unknown clip: $clipId" }

    val clip = ordered[index]
    val duration = clipTimelineDurationFrames(clip)
    val previous = ordered.getOrNull(index - 1)
    val next = ordered.getOrNull(index + 1)

    val minimum = previous?.let { clipTimelineEndFrameExclusive(it) } ?: 0L
    val maximum = next?.let { it.timelineStartFrame - duration }
        ?: (Long.MAX_VALUE - duration)

    require(maximum >= minimum) {
        "Clip $clipId has no legal non-overlapping move range"
    }
    return ClipMoveBounds(minimum, maximum)
}
