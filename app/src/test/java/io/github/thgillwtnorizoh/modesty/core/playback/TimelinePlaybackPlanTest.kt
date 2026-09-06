package io.github.thgillwtnorizoh.modesty.core.playback

import io.github.thgillwtnorizoh.modesty.core.model.AudioClip
import io.github.thgillwtnorizoh.modesty.core.model.AudioProject
import io.github.thgillwtnorizoh.modesty.core.model.AudioSource
import io.github.thgillwtnorizoh.modesty.core.model.AudioTrack
import io.github.thgillwtnorizoh.modesty.core.model.SampleRate
import io.github.thgillwtnorizoh.modesty.core.model.SourceRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class TimelinePlaybackPlanTest {
    private fun source() = AudioSource(
        id = "source",
        location = "test.wav",
        sampleRate = SampleRate(1_000),
        channelCount = 1,
        totalFrames = 1_000,
    )

    @Test
    fun representsDeleteGapAsSilenceBetweenSegments() {
        val source = source()
        val project = AudioProject(
            id = "project",
            title = "Gap",
            timelineRate = source.sampleRate,
            sources = mapOf(source.id to source),
            tracks = listOf(
                AudioTrack(
                    "track",
                    "Track",
                    listOf(
                        AudioClip("left", source.id, SourceRange(0, 200), 0),
                        AudioClip("right", source.id, SourceRange(700, 1_000), 700),
                    ),
                ),
            ),
        )

        val plan = TimelinePlaybackPlan.from(project)

        assertEquals(0, plan.timelineStartFrame)
        assertEquals(1_000, plan.timelineEndFrameExclusive)
        assertEquals("left", plan.segmentAt(100)?.clip?.id)
        assertNull(plan.segmentAt(500))
        assertEquals(700, plan.nextSegmentStartAfter(500))
        assertEquals("right", plan.segmentAt(700)?.clip?.id)
        assertEquals(850, plan.segmentAt(850)?.sourceFrameForTimeline(project, 850))
    }

    @Test
    fun rejectsOverlappingClipsUntilMixerExists() {
        val source = source()
        val project = AudioProject(
            id = "project",
            title = "Overlap",
            timelineRate = source.sampleRate,
            sources = mapOf(source.id to source),
            tracks = listOf(
                AudioTrack(
                    "track",
                    "Track",
                    listOf(
                        AudioClip("a", source.id, SourceRange(0, 600), 0),
                        AudioClip("b", source.id, SourceRange(600, 1_000), 500),
                    ),
                ),
            ),
        )

        assertThrows(IllegalArgumentException::class.java) {
            TimelinePlaybackPlan.from(project)
        }
    }
}
