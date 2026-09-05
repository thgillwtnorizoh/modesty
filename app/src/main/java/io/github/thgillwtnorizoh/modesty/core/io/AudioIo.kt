package io.github.thgillwtnorizoh.modesty.core.io

import io.github.thgillwtnorizoh.modesty.core.model.SampleRate

data class AudioStreamInfo(
    val sampleRate: SampleRate,
    val channelCount: Int,
    val totalFrames: Long,
)

enum class AudioFileFormat {
    WAV,
    FLAC,
    MP3,
    OGG_VORBIS,
    OPUS,
    AAC,
}

interface AudioDecoder : AutoCloseable {
    val info: AudioStreamInfo
    fun seekToSourceFrame(frame: Long)
    fun readInterleaved(output: FloatArray, maxFrames: Int): Int
}

interface AudioEncoder : AutoCloseable {
    fun writeInterleaved(input: FloatArray, frameCount: Int)
    fun finish()
}

interface AudioIoFactory {
    fun openDecoder(location: String): AudioDecoder

    fun openEncoder(
        location: String,
        format: AudioFileFormat,
        sampleRate: SampleRate,
        channelCount: Int,
    ): AudioEncoder
}
