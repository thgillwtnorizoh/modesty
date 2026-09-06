package io.github.thgillwtnorizoh.modesty.core.playback

import io.github.thgillwtnorizoh.modesty.core.model.AudioClip
import io.github.thgillwtnorizoh.modesty.core.model.AudioProject
import io.github.thgillwtnorizoh.modesty.core.model.AudioSource
import io.github.thgillwtnorizoh.modesty.core.model.AudioTrack
import io.github.thgillwtnorizoh.modesty.core.model.SampleRate
import io.github.thgillwtnorizoh.modesty.core.model.SourceRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SingleClipPlaybackPlanTest {
    @Test
    fun mapsTimelineFramesIntoTrimmedSourceRange() {
        val source = AudioSource(
            id = "source",
            location = "test.wav",
            sampleRate = SampleRate(48_000),
            channelCount = 2,
            totalFrames = 1_000,
        )
        val clip = AudioClip(
            id = "clip",
            sourceId = source.id,
            sourceRange = SourceRange(200, 700),
            timelineStartFrame = 100,
        )
        val project = AudioProject(
            id = "project",
            title = "Playback",
            timelineRate = source.sampleRate,
            sources = mapOf(source.id to source),
            tracks = listOf(AudioTrack("track", "Track", listOf(clip))),
        )

        val plan = SingleClipPlaybackPlan.from(project)

        assertEquals(100, plan.timelineStartFrame)
        assertEquals(600, plan.timelineEndFrameExclusive)
        assertEquals(200, plan.sourceFrameForTimeline(100))
        assertEquals(450, plan.sourceFrameForTimeline(350))
        assertEquals(700, plan.sourceFrameForTimeline(600))
        assertEquals(100, plan.clampTimelineFrame(-500))
        assertEquals(600, plan.clampTimelineFrame(5_000))
    }

    @Test
    fun rejectsMixedRatesUntilResamplerExists() {
        val source = AudioSource(
            id = "source",
            location = "test.wav",
            sampleRate = SampleRate(44_100),
            channelCount = 1,
            totalFrames = 1_000,
        )
        val project = AudioProject(
            id = "project",
            title = "Playback",
            timelineRate = SampleRate(48_000),
            sources = mapOf(source.id to source),
            tracks = listOf(
                AudioTrack(
                    "track",
                    "Track",
                    listOf(AudioClip("clip", source.id, SourceRange(0, 1_000), 0)),
                ),
            ),
        )

        assertThrows(IllegalArgumentException::class.java) {
            SingleClipPlaybackPlan.from(project)
        }
    }
}
