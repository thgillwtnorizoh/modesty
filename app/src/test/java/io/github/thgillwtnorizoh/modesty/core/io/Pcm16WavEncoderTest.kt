package io.github.thgillwtnorizoh.modesty.core.io

import io.github.thgillwtnorizoh.modesty.core.model.SampleRate
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Test

class Pcm16WavEncoderTest {
    @Test
    fun writesSeeklessRiffHeaderAndClampedPcm16Samples() {
        val bytes = ByteArrayOutputStream()
        Pcm16WavEncoder(
            rawOutput = bytes,
            sampleRate = SampleRate(48_000),
            channelCount = 2,
            totalFrames = 3,
        ).use { encoder ->
            encoder.writeInterleaved(
                floatArrayOf(
                    -1f, -0.5f,
                    0f, 0.5f,
                    1f, 2f,
                ),
                frameCount = 3,
            )
            encoder.finish()
        }

        val encoded = bytes.toByteArray()
        WavDecoder { ByteArrayInputStream(encoded) }.use { decoder ->
            assertEquals(48_000, decoder.info.sampleRate.hz)
            assertEquals(2, decoder.info.channelCount)
            assertEquals(3L, decoder.info.totalFrames)
            assertEquals(WavEncoding.PCM_INTEGER, decoder.metadata.encoding)
            assertEquals(16, decoder.metadata.bitsPerSample)

            val samples = FloatArray(6)
            assertEquals(3, decoder.readInterleaved(samples, 3))
            assertEquals(-1f, samples[0], 0.0001f)
            assertEquals(-0.5f, samples[1], 0.0001f)
            assertEquals(0f, samples[2], 0.0001f)
            assertEquals(0.5f, samples[3], 0.0001f)
            assertEquals(1f, samples[4], 0.0001f)
            assertEquals(1f, samples[5], 0.0001f)
        }
    }
}
