package io.github.thgillwtnorizoh.modesty.core.io

import io.github.thgillwtnorizoh.modesty.core.model.SampleRate
import java.io.BufferedOutputStream
import java.io.OutputStream
import kotlin.math.roundToInt

/**
 * Dependency-free RIFF/WAVE PCM16 encoder.
 *
 * The total frame count is known before writing, so the final RIFF/data sizes can be written in the
 * header immediately. This works with Android document-provider OutputStreams that are not seekable.
 * RF64 is intentionally left for a later brick.
 */
class Pcm16WavEncoder(
    rawOutput: OutputStream,
    sampleRate: SampleRate,
    private val channelCount: Int,
    private val totalFrames: Long,
) : AudioEncoder {
    private val output = BufferedOutputStream(rawOutput)
    private var framesWritten = 0L
    private var finished = false
    private var closed = false

    init {
        require(channelCount in 1..2) { "PCM16 WAV export currently supports mono or stereo" }
        require(totalFrames > 0) { "WAV export needs at least one frame" }

        val blockAlign = channelCount * BYTES_PER_SAMPLE
        require(totalFrames <= (UINT32_MAX - 36L) / blockAlign) {
            "WAV export exceeds RIFF's 4 GiB container limit; RF64 is not supported yet"
        }
        val dataSize = totalFrames * blockAlign.toLong()
        val riffSize = 36L + dataSize

        output.writeAscii("RIFF")
        output.writeUInt32LE(riffSize)
        output.writeAscii("WAVE")
        output.writeAscii("fmt ")
        output.writeUInt32LE(16)
        output.writeUInt16LE(WAVE_FORMAT_PCM)
        output.writeUInt16LE(channelCount)
        output.writeUInt32LE(sampleRate.hz.toLong())
        output.writeUInt32LE(sampleRate.hz.toLong() * blockAlign)
        output.writeUInt16LE(blockAlign)
        output.writeUInt16LE(BITS_PER_SAMPLE)
        output.writeAscii("data")
        output.writeUInt32LE(dataSize)
    }

    override fun writeInterleaved(input: FloatArray, frameCount: Int) {
        check(!finished && !closed) { "Encoder is already finished" }
        require(frameCount >= 0)
        require(input.size >= frameCount * channelCount) { "Input buffer is too small" }
        require(framesWritten + frameCount <= totalFrames) { "Encoder received more frames than declared" }

        val sampleCount = frameCount * channelCount
        for (index in 0 until sampleCount) {
            val value = floatToPcm16(input[index])
            output.write(value and 0xff)
            output.write((value ushr 8) and 0xff)
        }
        framesWritten += frameCount
    }

    override fun finish() {
        if (finished) return
        check(!closed) { "Encoder is closed" }
        require(framesWritten == totalFrames) {
            "WAV export wrote $framesWritten frames, expected $totalFrames"
        }
        output.flush()
        finished = true
    }

    override fun close() {
        if (closed) return
        try {
            if (!finished && framesWritten == totalFrames) finish()
        } finally {
            closed = true
            output.close()
        }
    }

    companion object {
        private const val WAVE_FORMAT_PCM = 1
        private const val BITS_PER_SAMPLE = 16
        private const val BYTES_PER_SAMPLE = 2
        private const val UINT32_MAX = 0xffff_ffffL

        internal fun floatToPcm16(sample: Float): Int {
            val clean = if (sample.isFinite()) sample else 0f
            return when {
                clean <= -1f -> -32_768
                clean >= 1f -> 32_767
                else -> (clean * 32_767f).roundToInt().coerceIn(-32_768, 32_767)
            }
        }
    }
}

private fun OutputStream.writeAscii(value: String) {
    write(value.toByteArray(Charsets.US_ASCII))
}

private fun OutputStream.writeUInt16LE(value: Int) {
    require(value in 0..0xffff)
    write(value and 0xff)
    write((value ushr 8) and 0xff)
}

private fun OutputStream.writeUInt32LE(value: Long) {
    require(value in 0..0xffff_ffffL)
    write((value and 0xff).toInt())
    write(((value ushr 8) and 0xff).toInt())
    write(((value ushr 16) and 0xff).toInt())
    write(((value ushr 24) and 0xff).toInt())
}
