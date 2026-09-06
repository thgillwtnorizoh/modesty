package io.github.thgillwtnorizoh.modesty.core.editing

import io.github.thgillwtnorizoh.modesty.core.model.AudioClip
import io.github.thgillwtnorizoh.modesty.core.model.AudioProject
import io.github.thgillwtnorizoh.modesty.core.model.AudioSource
import io.github.thgillwtnorizoh.modesty.core.model.AudioTrack
import io.github.thgillwtnorizoh.modesty.core.model.SampleRate
import io.github.thgillwtnorizoh.modesty.core.model.SourceRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AddSourceClipTest {
    private fun baseProject(): AudioProject {
        val source = AudioSource("source-a", "a.wav", SampleRate(1_000), 1, 1_000)
        return AudioProject(
            id = "project",
            title = "A",
            timelineRate = source.sampleRate,
            sources = mapOf(source.id to source),
            tracks = listOf(
                AudioTrack(
                    id = "track",
                    name = "Track",
                    clips = listOf(AudioClip("clip-a", source.id, SourceRange(0, 1_000), 0)),
                ),
            ),
        )
    }

    private fun sourceB(rate: Int = 1_000, channels: Int = 1): AudioSource =
        AudioSource("source-b", "b.wav", SampleRate(rate), channels, 500)

    @Test
    fun addsSecondSourceAndClipAtRequestedNonOverlappingPosition() {
        val source = sourceB()
        val result = AddSourceClip(
            trackId = "track",
            source = source,
            clip = AudioClip("clip-b", source.id, SourceRange(0, 500), 1_000),
        ).applyTo(baseProject())

        assertEquals(setOf("source-a", "source-b"), result.sources.keys)
        assertEquals(listOf("clip-a", "clip-b"), result.tracks.single().clips.map { it.id })
        assertEquals(1_000L, result.tracks.single().clips[1].timelineStartFrame)
    }

    @Test
    fun addSourceIsOneUndoRedoStepIncludingTheSourceObject() {
        val editor = ProjectEditor(baseProject())
        val source = sourceB()
        editor.apply(
            AddSourceClip(
                "track",
                source,
                AudioClip("clip-b", source.id, SourceRange(0, 500), 1_000),
            ),
        )

        assertEquals(1, editor.undoCount)
        assertEquals(2, editor.project.sources.size)
        assertEquals(2, editor.project.tracks.single().clips.size)

        editor.undo()
        assertEquals(1, editor.project.sources.size)
        assertEquals(1, editor.project.tracks.single().clips.size)

        editor.redo()
        assertEquals(2, editor.project.sources.size)
        assertEquals(2, editor.project.tracks.single().clips.size)
    }

    @Test
    fun rejectsSampleRateOrChannelMismatchUntilConvertersExist() {
        val rateMismatch = sourceB(rate = 2_000)
        assertThrows(IllegalArgumentException::class.java) {
            AddSourceClip(
                "track",
                rateMismatch,
                AudioClip("clip-b", rateMismatch.id, SourceRange(0, 500), 1_000),
            ).applyTo(baseProject())
        }

        val channelMismatch = sourceB(channels = 2)
        assertThrows(IllegalArgumentException::class.java) {
            AddSourceClip(
                "track",
                channelMismatch,
                AudioClip("clip-b", channelMismatch.id, SourceRange(0, 500), 1_000),
            ).applyTo(baseProject())
        }
    }

    @Test
    fun rejectsAnImportedClipThatOverlapsExistingAudio() {
        val source = sourceB()
        assertThrows(IllegalArgumentException::class.java) {
            AddSourceClip(
                "track",
                source,
                AudioClip("clip-b", source.id, SourceRange(0, 500), 750),
            ).applyTo(baseProject())
        }
    }
}
