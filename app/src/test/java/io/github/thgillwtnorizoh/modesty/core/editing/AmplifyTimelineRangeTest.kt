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

class AmplifyTimelineRangeTest {
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
            title = "Gain",
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
    fun amplifySelectionSplitsBoundariesAndChangesOnlySelectedGain() {
        val original = project()
        val result = AmplifyTimelineRange(
            trackId = "track",
            startTimelineFrame = 200,
            endTimelineFrameExclusive = 700,
            gainMultiplier = 2f,
            rightClipIdAtStart = "middle",
            rightClipIdAtEnd = "right",
        ).applyTo(original)

        val clips = result.tracks.single().clips
        assertEquals(3, clips.size)
        assertEquals(listOf(SourceRange(0, 200), SourceRange(200, 700), SourceRange(700, 1_000)), clips.map { it.sourceRange })
        assertEquals(1f, clips[0].gain, 0f)
        assertEquals(2f, clips[1].gain, 0f)
        assertEquals(1f, clips[2].gain, 0f)
        assertSame(original.sources["source"], result.sources["source"])
    }

    @Test
    fun repeatedAmplifyMultipliesExistingGainWithoutAddingBoundaryClips() {
        val once = AmplifyTimelineRange("track", 200, 700, 2f, "middle", "right").applyTo(project())
        val twice = AmplifyTimelineRange("track", 200, 700, 2f, "unused-a", "unused-b").applyTo(once)

        val clips = twice.tracks.single().clips
        assertEquals(3, clips.size)
        assertEquals(4f, clips[1].gain, 0f)
    }

    @Test
    fun oneAmplifyOperationIsOneUndoStep() {
        val editor = ProjectEditor(project())
        editor.apply(AmplifyTimelineRange("track", 200, 700, 0.5f, "middle", "right"))

        assertEquals(1, editor.undoCount)
        assertEquals(0.5f, editor.project.tracks.single().clips[1].gain, 0f)

        editor.undo()
        assertEquals(1, editor.project.tracks.single().clips.size)
        assertEquals(1f, editor.project.tracks.single().clips.single().gain, 0f)

        editor.redo()
        assertEquals(3, editor.project.tracks.single().clips.size)
        assertEquals(0.5f, editor.project.tracks.single().clips[1].gain, 0f)
    }
}
