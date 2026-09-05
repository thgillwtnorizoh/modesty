package io.github.thgillwtnorizoh.modesty.core.dsp

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class GainProcessorTest {
    @Test
    fun multipliesEveryInterleavedSample() {
        val input = floatArrayOf(0.25f, -0.5f, 1f, -1f)
        val output = FloatArray(input.size)

        GainProcessor(2f).process(input, output, frameCount = 2, channelCount = 2)

        assertArrayEquals(floatArrayOf(0.5f, -1f, 2f, -2f), output, 0.0001f)
    }
}
