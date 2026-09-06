package io.github.thgillwtnorizoh.modesty.core.editing

import io.github.thgillwtnorizoh.modesty.core.model.AudioClip
import io.github.thgillwtnorizoh.modesty.core.model.AudioProject

/**
 * Returns the one clip that completely contains a timeline selection.
 *
 * This is intentionally different from "selection overlaps audio": trim-to-selection only has a
 * clear meaning when both selection boundaries belong to the same clip. Other clips may exist
 * elsewhere on the track.
 */
fun AudioProject.clipContainingTimelineRange(
    trackId: String,
    startTimelineFrame: Long,
    endTimelineFrameExclusive: Long,
): AudioClip? {
    require(startTimelineFrame >= 0) { "Selection cannot start before frame zero" }
    require(endTimelineFrameExclusive > startTimelineFrame) { "Selection must contain timeline frames" }
    val track = tracks.firstOrNull { it.id == trackId } ?: error("Unknown track: $trackId")
    return track.clips.singleOrNull { clip ->
        startTimelineFrame >= clip.timelineStartFrame &&
            endTimelineFrameExclusive <= clipTimelineEndFrameExclusive(clip)
    }
}
