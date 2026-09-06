package io.github.thgillwtnorizoh.modesty.core.editing

import io.github.thgillwtnorizoh.modesty.core.model.AudioClip
import io.github.thgillwtnorizoh.modesty.core.model.AudioProject
import io.github.thgillwtnorizoh.modesty.core.model.AudioSource
import io.github.thgillwtnorizoh.modesty.core.model.AudioTrack
import io.github.thgillwtnorizoh.modesty.core.model.SampleRate
import io.github.thgillwtnorizoh.modesty.core.model.SourceRange
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class ClipMoveConstraintsTest {
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
            title = "Move",
            timelineRate = source.sampleRate,
            sources = mapOf(source.id to source),
            tracks = listOf(
                AudioTrack(
                    id = "track",
                    name = "Track",
                    clips = listOf(
                        AudioClip("left", source.id, SourceRange(0, 200), 0),
                        AudioClip("middle", source.id, SourceRange(200, 300), 300),
                        AudioClip("right", source.id, SourceRange(300, 500), 500),
                    ),
                ),
            ),
        )
    }

    @Test
    fun boundsUseNeighbourEdgesAsHardWalls() {
        val bounds = project().clipMoveBounds("track", "middle")

        assertEquals(200L, bounds.minimumStartFrame)
        assertEquals(400L, bounds.maximumStartFrame)
        assertEquals(200L, bounds.clamp(50))
        assertEquals(350L, bounds.clamp(350))
        assertEquals(400L, bounds.clamp(900))
    }

    @Test
    fun moveRejectsOverlap() {
        val original = project()

        try {
            MoveClip("track", "middle", 401).applyTo(original)
            fail("Expected overlap to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }

        val moved = MoveClip("track", "middle", 400).applyTo(original)
        assertEquals(400L, moved.tracks.single().clips.first { it.id == "middle" }.timelineStartFrame)
    }

    @Test
    fun dragStyleUpdatesStillProduceOneUndoEntry() {
        val editor = ProjectEditor(project())

        editor.beginTransaction("Move clip")
        editor.apply(MoveClip("track", "middle", 320))
        editor.apply(MoveClip("track", "middle", 360))
        editor.apply(MoveClip("track", "middle", 400))
        editor.commitTransaction()

        assertEquals(1, editor.undoCount)
        assertEquals(400L, editor.project.tracks.single().clips.first { it.id == "middle" }.timelineStartFrame)

        editor.undo()
        assertEquals(300L, editor.project.tracks.single().clips.first { it.id == "middle" }.timelineStartFrame)
    }
}
