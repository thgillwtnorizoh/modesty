package io.github.thgillwtnorizoh.modesty.core.editing

import io.github.thgillwtnorizoh.modesty.core.model.AudioClip
import io.github.thgillwtnorizoh.modesty.core.model.AudioProject
import io.github.thgillwtnorizoh.modesty.core.model.AudioSource
import io.github.thgillwtnorizoh.modesty.core.model.AudioTrack
import io.github.thgillwtnorizoh.modesty.core.model.SampleRate
import io.github.thgillwtnorizoh.modesty.core.model.SourceRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SelectionTargetsTest {
    private val rate = SampleRate(1_000)
    private val source = AudioSource("source", "memory", rate, 1, 2_000)
    private val project = AudioProject(
        id = "project",
        title = "Selection",
        timelineRate = rate,
        sources = mapOf(source.id to source),
        tracks = listOf(
            AudioTrack(
                id = "track",
                name = "Track",
                clips = listOf(
                    AudioClip("left", source.id, SourceRange(0, 500), 0),
                    AudioClip("right", source.id, SourceRange(500, 1_000), 700),
                ),
            ),
        ),
    )

    @Test
    fun selectionInsideOneClipTargetsThatClipEvenWhenOtherClipsExist() {
        val target = project.clipContainingTimelineRange("track", 750, 950)
        assertEquals("right", target?.id)
    }

    @Test
    fun selectionAcrossGapOrMultipleClipsHasNoTrimTarget() {
        assertNull(project.clipContainingTimelineRange("track", 450, 750))
        assertNull(project.clipContainingTimelineRange("track", 520, 680))
    }
}
