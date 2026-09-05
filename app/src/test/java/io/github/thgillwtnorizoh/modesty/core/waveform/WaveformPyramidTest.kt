package io.github.thgillwtnorizoh.modesty.core.waveform

import io.github.thgillwtnorizoh.modesty.core.io.AudioDecoder
import io.github.thgillwtnorizoh.modesty.core.io.AudioStreamInfo
import io.github.thgillwtnorizoh.modesty.core.model.SampleRate
import org.junit.Assert.assertEquals
import org.junit.Test

class WaveformPyramidTest {
    @Test
    fun buildsLevelsAndReturnsAResolutionNearTheRequest() {
        val decoder = ArrayDecoder(floatArrayOf(-1f, -0.5f, 0f, 0.5f, 1f, 0.25f, -0.25f, 0f))
        val pyramid = WaveformPyramidBuilder.build(decoder, baseBucketFrames = 2, readBlockFrames = 3)

        assertEquals(3, pyramid.levels.size)
        assertEquals(4, pyramid.levels[0].channels[0].size)
        assertEquals(2, pyramid.levels[1].channels[0].size)
        assertEquals(1, pyramid.levels[2].channels[0].size)
        assertEquals(-1f, pyramid.levels[0].channels[0][0].min, 0f)
        assertEquals(-0.5f, pyramid.levels[0].channels[0][0].max, 0f)

        val cache = InMemoryWaveformCache().put("test", pyramid)
        val overview = cache.read("test", 0, 0, 8, 2)
        assertEquals(2, overview.size)
        assertEquals(-1f, overview[0].min, 0f)
        assertEquals(0.5f, overview[0].max, 0f)
        assertEquals(-0.25f, overview[1].min, 0f)
        assertEquals(1f, overview[1].max, 0f)
    }

    private class ArrayDecoder(private val samples: FloatArray) : AudioDecoder {
        override val info = AudioStreamInfo(SampleRate(48_000), 1, samples.size.toLong())
        private var position = 0

        override fun seekToSourceFrame(frame: Long) {
            position = frame.toInt()
        }

        override fun readInterleaved(output: FloatArray, maxFrames: Int): Int {
            val count = minOf(maxFrames, samples.size - position)
            if (count <= 0) return 0
            samples.copyInto(output, 0, position, position + count)
            position += count
            return count
        }

        override fun close() = Unit
    }
}
