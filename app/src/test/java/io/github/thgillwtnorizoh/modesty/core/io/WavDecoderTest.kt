package io.github.thgillwtnorizoh.modesty.core.io

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class WavDecoderTest {
    @Test
    fun decodesPcm16AndSeeksByFrame() {
        val wav = pcm16Wav(shortArrayOf(Short.MIN_VALUE, -16384, 0, 16384, Short.MAX_VALUE), 48_000)
        val decoder = WavDecoder { ByteArrayInputStream(wav) }

        assertEquals(48_000, decoder.info.sampleRate.hz)
        assertEquals(1, decoder.info.channelCount)
        assertEquals(5, decoder.info.totalFrames)
        assertEquals(WavEncoding.PCM_INTEGER, decoder.metadata.encoding)
        assertEquals(16, decoder.metadata.bitsPerSample)

        val output = FloatArray(5)
        assertEquals(5, decoder.readInterleaved(output, 5))
        assertEquals(-1f, output[0], 0.00001f)
        assertEquals(-0.5f, output[1], 0.00001f)
        assertEquals(0f, output[2], 0.00001f)
        assertEquals(0.5f, output[3], 0.00001f)
        assertEquals(0.9999695f, output[4], 0.00001f)

        decoder.seekToSourceFrame(3)
        val tail = FloatArray(2)
        assertEquals(2, decoder.readInterleaved(tail, 2))
        assertEquals(0.5f, tail[0], 0.00001f)
        assertEquals(0.9999695f, tail[1], 0.00001f)
        decoder.close()
    }

    @Test
    fun decodesFloat32() {
        val wav = float32Wav(floatArrayOf(-0.75f, 0.25f, 1.25f), 44_100)
        val decoder = WavDecoder { ByteArrayInputStream(wav) }
        val output = FloatArray(3)

        assertEquals(WavEncoding.IEEE_FLOAT, decoder.metadata.encoding)
        assertEquals(32, decoder.metadata.bitsPerSample)
        assertEquals(3, decoder.readInterleaved(output, 3))
        assertEquals(-0.75f, output[0], 0f)
        assertEquals(0.25f, output[1], 0f)
        assertEquals(1.25f, output[2], 0f)
        decoder.close()
    }

    private fun pcm16Wav(samples: ShortArray, sampleRate: Int): ByteArray {
        val payload = ByteArrayOutputStream()
        samples.forEach { write16(payload, it.toInt() and 0xffff) }
        return wavContainer(formatTag = 1, bits = 16, sampleRate = sampleRate, payload = payload.toByteArray())
    }

    private fun float32Wav(samples: FloatArray, sampleRate: Int): ByteArray {
        val payload = ByteArrayOutputStream()
        samples.forEach { write32(payload, it.toBits()) }
        return wavContainer(formatTag = 3, bits = 32, sampleRate = sampleRate, payload = payload.toByteArray())
    }

    private fun wavContainer(formatTag: Int, bits: Int, sampleRate: Int, payload: ByteArray): ByteArray {
        val bytesPerSample = bits / 8
        val output = ByteArrayOutputStream()
        output.write("RIFF".toByteArray())
        write32(output, 36 + payload.size)
        output.write("WAVE".toByteArray())
        output.write("fmt ".toByteArray())
        write32(output, 16)
        write16(output, formatTag)
        write16(output, 1)
        write32(output, sampleRate)
        write32(output, sampleRate * bytesPerSample)
        write16(output, bytesPerSample)
        write16(output, bits)
        output.write("data".toByteArray())
        write32(output, payload.size)
        output.write(payload)
        return output.toByteArray()
    }

    private fun write16(output: ByteArrayOutputStream, value: Int) {
        output.write(value and 0xff)
        output.write((value ushr 8) and 0xff)
    }

    private fun write32(output: ByteArrayOutputStream, value: Int) {
        repeat(4) { byte -> output.write((value ushr (byte * 8)) and 0xff) }
    }
}
