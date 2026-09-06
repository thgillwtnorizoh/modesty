package io.github.thgillwtnorizoh.modesty.core.editing

import io.github.thgillwtnorizoh.modesty.core.model.AudioClip
import io.github.thgillwtnorizoh.modesty.core.model.AudioProject
import io.github.thgillwtnorizoh.modesty.core.model.AudioSource
import io.github.thgillwtnorizoh.modesty.core.model.AudioTrack
import io.github.thgillwtnorizoh.modesty.core.model.SampleRate
import io.github.thgillwtnorizoh.modesty.core.model.SourceRange
import io.github.thgillwtnorizoh.modesty.core.playback.SingleClipPlaybackPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ProjectEditorTest {
    private fun project(): AudioProject {
        val source = AudioSource(
            id = "source-1",
            location = "test.wav",
            sampleRate = SampleRate(48_000),
            channelCount = 2,
            totalFrames = 480_000,
        )
        val clip = AudioClip(
            id = "clip-1",
            sourceId = source.id,
            sourceRange = SourceRange(0, 96_000),
            timelineStartFrame = 0,
        )
        return AudioProject(
            id = "project-1",
            title = "Test",
            sources = mapOf(source.id to source),
            tracks = listOf(AudioTrack("track-1", "Track 1", listOf(clip))),
        )
    }

    @Test
    fun trimChangesMetadataWithoutReplacingSource() {
        val original = project()
        val editor = ProjectEditor(original)

        editor.apply(TrimClip("track-1", "clip-1", 24_000, 72_000))

        val clip = editor.project.tracks.single().clips.single()
        assertEquals(SourceRange(24_000, 72_000), clip.sourceRange)
        assertEquals(24_000, clip.timelineStartFrame)
        assertSame(original.sources["source-1"], editor.project.sources["source-1"])
    }

    @Test
    fun trimAndUndoKeepPlaybackWindowAligned() {
        val editor = ProjectEditor(project())

        editor.apply(TrimClip("track-1", "clip-1", 24_000, 72_000))
        val trimmedPlan = SingleClipPlaybackPlan.from(editor.project)

        assertEquals(24_000, trimmedPlan.timelineStartFrame)
        assertEquals(72_000, trimmedPlan.timelineEndFrameExclusive)
        assertEquals(24_000, trimmedPlan.sourceFrameForTimeline(24_000))
        assertEquals(72_000, trimmedPlan.sourceFrameForTimeline(72_000))

        editor.undo()
        val restoredPlan = SingleClipPlaybackPlan.from(editor.project)

        assertEquals(0, restoredPlan.timelineStartFrame)
        assertEquals(96_000, restoredPlan.timelineEndFrameExclusive)
        assertEquals(0, restoredPlan.sourceFrameForTimeline(0))
        assertEquals(96_000, restoredPlan.sourceFrameForTimeline(96_000))
    }

    @Test
    fun gestureTransactionProducesOneUndoEntry() {
        val editor = ProjectEditor(project())

        editor.beginTransaction("Move clip")
        editor.apply(MoveClip("track-1", "clip-1", 100))
        editor.apply(MoveClip("track-1", "clip-1", 200))
        editor.apply(MoveClip("track-1", "clip-1", 300))
        editor.commitTransaction()

        assertEquals(1, editor.undoCount)
        assertEquals(300, editor.project.tracks.single().clips.single().timelineStartFrame)

        editor.undo()
        assertEquals(0, editor.project.tracks.single().clips.single().timelineStartFrame)
    }

    @Test
    fun splitCreatesTwoClipsReferencingSameSource() {
        val editor = ProjectEditor(project())

        editor.apply(SplitClip("track-1", "clip-1", 48_000, "clip-2"))

        val clips = editor.project.tracks.single().clips
        assertEquals(2, clips.size)
        assertEquals("source-1", clips[0].sourceId)
        assertEquals("source-1", clips[1].sourceId)
        assertEquals(SourceRange(0, 48_000), clips[0].sourceRange)
        assertEquals(SourceRange(48_000, 96_000), clips[1].sourceRange)
        assertEquals(48_000, clips[1].timelineStartFrame)
    }
}
