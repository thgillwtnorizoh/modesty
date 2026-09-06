package io.github.thgillwtnorizoh.modesty.core.render

import io.github.thgillwtnorizoh.modesty.core.io.AudioDecoder
import io.github.thgillwtnorizoh.modesty.core.io.AudioEncoder
import io.github.thgillwtnorizoh.modesty.core.io.AudioStreamInfo
import io.github.thgillwtnorizoh.modesty.core.model.AudioClip
import io.github.thgillwtnorizoh.modesty.core.model.AudioProject
import io.github.thgillwtnorizoh.modesty.core.model.AudioSource
import io.github.thgillwtnorizoh.modesty.core.model.AudioTrack
import io.github.thgillwtnorizoh.modesty.core.model.SampleRate
import io.github.thgillwtnorizoh.modesty.core.model.SourceRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineRendererTest {
    @Test
    fun rendersClipGainAndTimelineSilenceIntoOneSequentialStream() {
        val sourceSamples = floatArrayOf(
            0.1f, 0.2f, 0.3f, 0.4f, 0.5f,
            0.6f, 0.7f, 0.8f, 0.9f, 1f,
        )
        val source = AudioSource(
            id = "source",
            location = "memory",
            sampleRate = SampleRate(1_000),
            channelCount = 1,
            totalFrames = sourceSamples.size.toLong(),
        )
        val project = AudioProject(
            id = "project",
            title = "Bento",
            timelineRate = source.sampleRate,
            sources = mapOf(source.id to source),
            tracks = listOf(
                AudioTrack(
                    id = "track",
                    name = "Track",
                    clips = listOf(
                        AudioClip("left", source.id, SourceRange(0, 3), timelineStartFrame = 2, gain = 2f),
                        AudioClip("right", source.id, SourceRange(5, 8), timelineStartFrame = 6, gain = 0.5f),
                    ),
                ),
            ),
        )

        val renderer = TimelineRenderer(project) {
            MemoryDecoder(source.sampleRate, sourceSamples)
        }
        val encoder = RecordingEncoder(channelCount = 1)
        val progress = mutableListOf<Float>()

        renderer.render(encoder) { progress += it }

        assertEquals(1_000, renderer.outputInfo.sampleRate.hz)
        assertEquals(1, renderer.outputInfo.channelCount)
        assertEquals(7L, renderer.outputInfo.totalFrames)
        assertEquals(7, encoder.samples.size)
        assertEquals(0.2f, encoder.samples[0], 0.0001f)
        assertEquals(0.4f, encoder.samples[1], 0.0001f)
        assertEquals(0.6f, encoder.samples[2], 0.0001f)
        assertEquals(0f, encoder.samples[3], 0.0001f)
        assertEquals(0.3f, encoder.samples[4], 0.0001f)
        assertEquals(0.35f, encoder.samples[5], 0.0001f)
        assertEquals(0.4f, encoder.samples[6], 0.0001f)
        assertTrue(encoder.finished)
        assertEquals(1f, progress.last(), 0f)
    }

    @Test
    fun rendererSwitchesDecodersAcrossMultipleSourcesInTimelineOrder() {
        val rate = SampleRate(1_000)
        val aSamples = floatArrayOf(0.1f, 0.2f)
        val bSamples = floatArrayOf(0.7f, 0.8f)
        val sourceA = AudioSource("a", "a.wav", rate, 1, 2)
        val sourceB = AudioSource("b", "b.wav", rate, 1, 2)
        val project = AudioProject(
            id = "multi",
            title = "A+B",
            timelineRate = rate,
            sources = linkedMapOf(sourceA.id to sourceA, sourceB.id to sourceB),
            tracks = listOf(
                AudioTrack(
                    "track",
                    "Track",
                    listOf(
                        AudioClip("a-clip", sourceA.id, SourceRange(0, 2), 0),
                        AudioClip("b-clip", sourceB.id, SourceRange(0, 2), 2),
                    ),
                ),
            ),
        )

        val renderer = TimelineRenderer(project) { source ->
            MemoryDecoder(rate, if (source.id == sourceA.id) aSamples else bSamples)
        }
        val encoder = RecordingEncoder(1)
        renderer.render(encoder)

        assertEquals(listOf(0.1f, 0.2f, 0.7f, 0.8f), encoder.samples)
        assertTrue(encoder.finished)
    }

    private class MemoryDecoder(
        private val rate: SampleRate,
        private val samples: FloatArray,
    ) : AudioDecoder {
        override val info = AudioStreamInfo(rate, channelCount = 1, totalFrames = samples.size.toLong())
        private var frame = 0

        override fun seekToSourceFrame(frame: Long) {
            require(frame in 0..samples.size.toLong())
            this.frame = frame.toInt()
        }

        override fun readInterleaved(output: FloatArray, maxFrames: Int): Int {
            val count = minOf(maxFrames, samples.size - frame)
            for (index in 0 until count) output[index] = samples[frame + index]
            frame += count
            return count
        }

        override fun close() = Unit
    }

    private class RecordingEncoder(
        private val channelCount: Int,
    ) : AudioEncoder {
        val samples = mutableListOf<Float>()
        var finished = false
            private set

        override fun writeInterleaved(input: FloatArray, frameCount: Int) {
            repeat(frameCount * channelCount) { index -> samples += input[index] }
        }

        override fun finish() {
            finished = true
        }

        override fun close() = Unit
    }
}
