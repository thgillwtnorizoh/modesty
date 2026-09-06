package io.github.thgillwtnorizoh.modesty.core.editing

import io.github.thgillwtnorizoh.modesty.core.model.AudioClip
import io.github.thgillwtnorizoh.modesty.core.model.AudioProject
import io.github.thgillwtnorizoh.modesty.core.model.AudioSource
import io.github.thgillwtnorizoh.modesty.core.model.AudioTrack
import io.github.thgillwtnorizoh.modesty.core.model.SampleRate
import io.github.thgillwtnorizoh.modesty.core.model.SourceRange
import org.junit.Assert.assertEquals
import org.junit.Test

class AppendWholeSourceAtEndTest {
    @Test
    fun appendsWholeImportedSourceImmediatelyAfterLatestClip() {
        val rate = SampleRate(1_000)
        val sourceA = AudioSource("a", "a.wav", rate, 1, 1_000)
        val sourceB = AudioSource("b", "b.wav", rate, 1, 400)
        val project = AudioProject(
            id = "project",
            title = "A",
            timelineRate = rate,
            sources = mapOf(sourceA.id to sourceA),
            tracks = listOf(
                AudioTrack(
                    id = "track",
                    name = "Track",
                    clips = listOf(
                        AudioClip("a1", sourceA.id, SourceRange(0, 200), 0),
                        AudioClip("a2", sourceA.id, SourceRange(300, 600), 500),
                    ),
                ),
            ),
        )

        val operation = project.appendWholeSourceAtEndOperation("track", sourceB, "b1")
        val result = operation.applyTo(project)
        val appended = result.tracks.single().clips.last()

        assertEquals("b", appended.sourceId)
        assertEquals(SourceRange(0, 400), appended.sourceRange)
        assertEquals(800L, appended.timelineStartFrame)
    }
}
