package io.github.thgillwtnorizoh.modesty.core.editing

import io.github.thgillwtnorizoh.modesty.core.model.AudioClip
import io.github.thgillwtnorizoh.modesty.core.model.AudioProject
import io.github.thgillwtnorizoh.modesty.core.model.AudioSource
import io.github.thgillwtnorizoh.modesty.core.model.AudioTrack
import io.github.thgillwtnorizoh.modesty.core.model.SampleRate
import io.github.thgillwtnorizoh.modesty.core.model.SourceRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class TimelineEditOperationTest {
    private fun project(): AudioProject {
        val source = AudioSource(
            id = "source",
            location = "test.wav",
            sampleRate = SampleRate(1_000),
            channelCount = 1,
            totalFrames = 1_000,
        )
        return AudioProject(
            id = "project",
            title = "Timeline",
            timelineRate = source.sampleRate,
            sources = mapOf(source.id to source),
            tracks = listOf(
                AudioTrack(
                    id = "track",
                    name = "Track",
                    clips = listOf(
                        AudioClip(
                            id = "clip",
                            sourceId = source.id,
                            sourceRange = SourceRange(0, 1_000),
                            timelineStartFrame = 0,
                        ),
                    ),
                ),
            ),
        )
    }

    @Test
    fun splitSelectionCreatesThreeContiguousClipsWithoutReplacingSource() {
        val original = project()
        val result = SplitTimelineRange(
            trackId = "track",
            startTimelineFrame = 200,
            endTimelineFrameExclusive = 700,
            rightClipIdAtStart = "middle",
            rightClipIdAtEnd = "right",
        ).applyTo(original)

        val clips = result.tracks.single().clips
        assertEquals(3, clips.size)
        assertEquals(SourceRange(0, 200), clips[0].sourceRange)
        assertEquals(SourceRange(200, 700), clips[1].sourceRange)
        assertEquals(SourceRange(700, 1_000), clips[2].sourceRange)
        assertEquals(listOf(0L, 200L, 700L), clips.map { it.timelineStartFrame })
        assertSame(original.sources["source"], result.sources["source"])
    }

    @Test
    fun deleteMiddleLeavesTimelineGapAndTwoSourceReferences() {
        val original = project()
        val result = DeleteTimelineRange(
            trackId = "track",
            startTimelineFrame = 200,
            endTimelineFrameExclusive = 700,
            rightClipId = "right",
        ).applyTo(original)

        val clips = result.tracks.single().clips
        assertEquals(2, clips.size)
        assertEquals(SourceRange(0, 200), clips[0].sourceRange)
        assertEquals(SourceRange(700, 1_000), clips[1].sourceRange)
        assertEquals(0, clips[0].timelineStartFrame)
        assertEquals(700, clips[1].timelineStartFrame)
        assertSame(original.sources["source"], result.sources["source"])
    }

    @Test
    fun splitDeleteUndoRedoWalksTimelineStates() {
        val editor = ProjectEditor(project())
        editor.apply(SplitTimelineRange("track", 200, 700, "middle", "right"))
        assertEquals(3, editor.project.tracks.single().clips.size)

        editor.apply(DeleteTimelineRange("track", 200, 700, "unused"))
        assertEquals(
            listOf(SourceRange(0, 200), SourceRange(700, 1_000)),
            editor.project.tracks.single().clips.map { it.sourceRange },
        )

        editor.undo()
        assertEquals(3, editor.project.tracks.single().clips.size)
        editor.undo()
        assertEquals(1, editor.project.tracks.single().clips.size)

        editor.redo()
        assertEquals(3, editor.project.tracks.single().clips.size)
        editor.redo()
        assertEquals(2, editor.project.tracks.single().clips.size)
    }
}
