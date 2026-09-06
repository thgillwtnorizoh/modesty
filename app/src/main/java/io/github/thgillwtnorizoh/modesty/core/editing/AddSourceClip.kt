package io.github.thgillwtnorizoh.modesty.core.editing

import io.github.thgillwtnorizoh.modesty.core.model.AudioClip
import io.github.thgillwtnorizoh.modesty.core.model.AudioProject
import io.github.thgillwtnorizoh.modesty.core.model.AudioSource

/**
 * Adds one immutable source plus its first clip to an existing track.
 *
 * Brick #9 deliberately keeps the current realtime/export contract simple: imported sources must
 * match the project's sample rate and the channel layout already in use. Resampling and channel
 * conversion belong to later bricks.
 */
data class AddSourceClip(
    val trackId: String,
    val source: AudioSource,
    val clip: AudioClip,
) : EditOperation {
    override val description: String = "Add audio source"

    override fun applyTo(project: AudioProject): AudioProject {
        require(source.id !in project.sources) { "Source id already exists: ${source.id}" }
        require(clip.sourceId == source.id) { "Imported clip must reference the imported source" }
        require(clip.sourceRange.endFrameExclusive <= source.totalFrames) {
            "Imported clip extends beyond source ${source.id}"
        }
        require(source.sampleRate == project.timelineRate) {
            "Imported WAV must match the project sample rate (${project.timelineRate.hz} Hz)"
        }

        val existingChannelCount = project.sources.values.firstOrNull()?.channelCount
        if (existingChannelCount != null) {
            require(source.channelCount == existingChannelCount) {
                "Imported WAV must match the project channel count ($existingChannelCount)"
            }
        }

        require(project.tracks.none { track -> track.clips.any { it.id == clip.id } }) {
            "Clip id already exists: ${clip.id}"
        }

        val trackIndex = project.tracks.indexOfFirst { it.id == trackId }
        require(trackIndex >= 0) { "Unknown track: $trackId" }
        val track = project.tracks[trackIndex]

        val clipDuration = sourceFramesToProjectFrames(
            sourceFrames = clip.sourceRange.lengthFrames,
            sourceRate = source.sampleRate.hz,
            projectRate = project.timelineRate.hz,
        )
        val clipEnd = clip.timelineStartFrame + clipDuration
        require(clipEnd >= clip.timelineStartFrame) { "Imported clip timeline position overflowed" }

        track.clips.forEach { existing ->
            val existingEnd = project.clipTimelineEndFrameExclusive(existing)
            require(clipEnd <= existing.timelineStartFrame || clip.timelineStartFrame >= existingEnd) {
                "Imported clip would overlap an existing clip"
            }
        }

        val newTrack = track.copy(clips = (track.clips + clip).sortedBy { it.timelineStartFrame })
        val newTracks = project.tracks.toMutableList().apply { this[trackIndex] = newTrack }
        return project.copy(
            sources = project.sources + (source.id to source),
            tracks = newTracks,
        ).validate()
    }

    private fun sourceFramesToProjectFrames(sourceFrames: Long, sourceRate: Int, projectRate: Int): Long =
        if (sourceRate == projectRate) sourceFrames
        else kotlin.math.round(sourceFrames.toDouble() * projectRate / sourceRate).toLong()
}
