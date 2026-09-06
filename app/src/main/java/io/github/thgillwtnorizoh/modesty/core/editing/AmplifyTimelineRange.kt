package io.github.thgillwtnorizoh.modesty.core.editing

import io.github.thgillwtnorizoh.modesty.core.model.AudioProject

/**
 * Multiplies clip gain for every piece of audio under a timeline selection.
 *
 * The selection boundaries are split first, so the gain change stays nondestructive and is
 * represented entirely by clip metadata. Existing silence remains silence and source media is
 * never rewritten.
 */
data class AmplifyTimelineRange(
    val trackId: String,
    val startTimelineFrame: Long,
    val endTimelineFrameExclusive: Long,
    val gainMultiplier: Float,
    val rightClipIdAtStart: String,
    val rightClipIdAtEnd: String,
) : EditOperation {
    override val description: String = "Amplify selection"

    override fun applyTo(project: AudioProject): AudioProject {
        require(startTimelineFrame >= 0)
        require(endTimelineFrameExclusive > startTimelineFrame)
        require(gainMultiplier >= 0f && gainMultiplier.isFinite()) {
            "Gain multiplier must be finite and non-negative"
        }
        require(rightClipIdAtStart.isNotBlank())
        require(rightClipIdAtEnd.isNotBlank())
        require(rightClipIdAtStart != rightClipIdAtEnd)

        val split = SplitTimelineRange(
            trackId = trackId,
            startTimelineFrame = startTimelineFrame,
            endTimelineFrameExclusive = endTimelineFrameExclusive,
            rightClipIdAtStart = rightClipIdAtStart,
            rightClipIdAtEnd = rightClipIdAtEnd,
        ).applyTo(project)

        val trackIndex = split.tracks.indexOfFirst { it.id == trackId }
        require(trackIndex >= 0) { "Unknown track: $trackId" }
        val track = split.tracks[trackIndex]

        val newClips = track.clips.map { clip ->
            val clipStart = clip.timelineStartFrame
            val clipEnd = split.clipTimelineEndFrameExclusive(clip)
            val overlapsSelection = maxOf(clipStart, startTimelineFrame) <
                minOf(clipEnd, endTimelineFrameExclusive)
            val fullyInsideSelection = clipStart >= startTimelineFrame &&
                clipEnd <= endTimelineFrameExclusive

            if (!overlapsSelection) {
                clip
            } else {
                check(fullyInsideSelection) {
                    "Amplify selection boundary did not split a partially selected clip"
                }
                val newGain = clip.gain * gainMultiplier
                require(newGain.isFinite() && newGain >= 0f) { "Resulting clip gain is invalid" }
                clip.copy(gain = newGain)
            }
        }

        val newTracks = split.tracks.toMutableList().apply {
            this[trackIndex] = track.copy(clips = newClips)
        }
        return split.copy(tracks = newTracks).validate()
    }
}
