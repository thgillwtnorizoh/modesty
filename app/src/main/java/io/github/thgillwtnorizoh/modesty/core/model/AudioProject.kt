package io.github.thgillwtnorizoh.modesty.core.model

import kotlin.math.roundToLong

@JvmInline
value class SampleRate(val hz: Int) {
    init {
        require(hz > 0) { "Sample rate must be positive" }
    }
}

data class SourceRange(
    val startFrame: Long,
    val endFrameExclusive: Long,
) {
    init {
        require(startFrame >= 0) { "Source range cannot start before frame zero" }
        require(endFrameExclusive > startFrame) { "Source range must contain at least one frame" }
    }

    val lengthFrames: Long
        get() = endFrameExclusive - startFrame
}

data class AudioSource(
    val id: String,
    val location: String,
    val sampleRate: SampleRate,
    val channelCount: Int,
    val totalFrames: Long,
) {
    init {
        require(id.isNotBlank()) { "Source id cannot be blank" }
        require(location.isNotBlank()) { "Source location cannot be blank" }
        require(channelCount > 0) { "Channel count must be positive" }
        require(totalFrames > 0) { "Audio source must contain audio" }
    }
}

data class AudioClip(
    val id: String,
    val sourceId: String,
    val sourceRange: SourceRange,
    val timelineStartFrame: Long,
    val gain: Float = 1f,
    val fadeInSourceFrames: Long = 0,
    val fadeOutSourceFrames: Long = 0,
) {
    init {
        require(id.isNotBlank()) { "Clip id cannot be blank" }
        require(sourceId.isNotBlank()) { "Source id cannot be blank" }
        require(timelineStartFrame >= 0) { "Clip cannot start before the timeline" }
        require(gain >= 0f && gain.isFinite()) { "Gain must be finite and non-negative" }
        require(fadeInSourceFrames >= 0 && fadeOutSourceFrames >= 0) { "Fade lengths cannot be negative" }
        require(fadeInSourceFrames + fadeOutSourceFrames <= sourceRange.lengthFrames) {
            "Combined fades cannot exceed the clip source range"
        }
    }
}

data class AudioTrack(
    val id: String,
    val name: String,
    val clips: List<AudioClip> = emptyList(),
) {
    init {
        require(id.isNotBlank()) { "Track id cannot be blank" }
    }
}

data class AudioProject(
    val id: String,
    val title: String,
    val timelineRate: SampleRate = SampleRate(48_000),
    val sources: Map<String, AudioSource> = emptyMap(),
    val tracks: List<AudioTrack> = emptyList(),
) {
    init {
        require(id.isNotBlank()) { "Project id cannot be blank" }
        validate()
    }

    fun sourceFramesToProjectFrames(sourceId: String, sourceFrames: Long): Long {
        require(sourceFrames >= 0)
        val source = sources[sourceId] ?: error("Unknown source: $sourceId")
        return (sourceFrames.toDouble() * timelineRate.hz / source.sampleRate.hz).roundToLong()
    }

    fun clipTimelineDurationFrames(clip: AudioClip): Long =
        sourceFramesToProjectFrames(clip.sourceId, clip.sourceRange.lengthFrames)

    fun validate(): AudioProject {
        val trackIds = mutableSetOf<String>()
        val clipIds = mutableSetOf<String>()

        tracks.forEach { track ->
            require(trackIds.add(track.id)) { "Duplicate track id: ${track.id}" }

            track.clips.forEach { clip ->
                require(clipIds.add(clip.id)) { "Duplicate clip id: ${clip.id}" }
                val source = sources[clip.sourceId] ?: error("Clip ${clip.id} references missing source ${clip.sourceId}")
                require(clip.sourceRange.endFrameExclusive <= source.totalFrames) {
                    "Clip ${clip.id} extends beyond source ${source.id}"
                }
            }
        }
        return this
    }
}
