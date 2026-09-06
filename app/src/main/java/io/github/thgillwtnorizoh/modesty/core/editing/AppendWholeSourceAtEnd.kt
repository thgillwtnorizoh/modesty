package io.github.thgillwtnorizoh.modesty.core.editing

import io.github.thgillwtnorizoh.modesty.core.model.AudioClip
import io.github.thgillwtnorizoh.modesty.core.model.AudioProject
import io.github.thgillwtnorizoh.modesty.core.model.AudioSource
import io.github.thgillwtnorizoh.modesty.core.model.SourceRange

/**
 * Builds the normal AddSourceClip operation used when a complete imported source should be placed
 * immediately after the current final clip on a track.
 *
 * Quick Join deliberately uses this exact factory rather than owning a second join implementation.
 */
fun AudioProject.appendWholeSourceAtEndOperation(
    trackId: String,
    source: AudioSource,
    clipId: String,
): AddSourceClip {
    val track = tracks.firstOrNull { it.id == trackId }
        ?: error("Unknown track: $trackId")
    val appendAt = track.clips.maxOfOrNull { clipTimelineEndFrameExclusive(it) } ?: 0L
    return AddSourceClip(
        trackId = trackId,
        source = source,
        clip = AudioClip(
            id = clipId,
            sourceId = source.id,
            sourceRange = SourceRange(0, source.totalFrames),
            timelineStartFrame = appendAt,
        ),
    )
}
