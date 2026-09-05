package io.github.thgillwtnorizoh.modesty.core.io

import io.github.thgillwtnorizoh.modesty.core.model.SampleRate
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.InputStream

/** Encoding actually stored in the WAV data chunk. */
enum class WavEncoding {
    PCM_INTEGER,
    IEEE_FLOAT,
}

data class WavMetadata(
    val encoding: WavEncoding,
    val bitsPerSample: Int,
    val blockAlign: Int,
    val dataSizeBytes: Long,
    val dataOffsetBytes: Long,
    val info: AudioStreamInfo,
)

/**
 * Small, dependency-free RIFF/WAVE decoder used by the foundation.
 *
 * It intentionally accepts an InputStream factory instead of a File/Uri. That keeps the decoder
 * independent from Android and also lets seek work for providers where the underlying descriptor is
 * not directly seekable: seeking simply reopens the stream and skips to the requested frame.
 */
class WavDecoder(
    private val streamFactory: () -> InputStream,
) : AudioDecoder {
    val metadata: WavMetadata = parseHeader(streamFactory())

    override val info: AudioStreamInfo
        get() = metadata.info

    private var stream: BufferedInputStream? = null
    private var currentFrame: Long = 0

    init {
        seekToSourceFrame(0)
    }

    override fun seekToSourceFrame(frame: Long) {
        require(frame in 0..info.totalFrames) { "Frame $frame is outside 0..${info.totalFrames}" }
        stream?.close()
        val reopened = BufferedInputStream(streamFactory())
        reopened.skipFully(metadata.dataOffsetBytes + frame * metadata.blockAlign)
        stream = reopened
        currentFrame = frame
    }

    override fun readInterleaved(output: FloatArray, maxFrames: Int): Int {
        require(maxFrames >= 0) { "maxFrames cannot be negative" }
        if (maxFrames == 0 || currentFrame >= info.totalFrames) return 0

        val framesRequested = minOf(maxFrames.toLong(), info.totalFrames - currentFrame).toInt()
        require(output.size >= framesRequested * info.channelCount) { "Output buffer is too small" }

        val bytesRequested = framesRequested * metadata.blockAlign
        val bytes = ByteArray(bytesRequested)
        val input = requireNotNull(stream) { "Decoder is closed" }
        val bytesRead = input.readUpTo(bytes, bytesRequested)
        val framesRead = bytesRead / metadata.blockAlign
        if (framesRead <= 0) return 0

        val bytesPerSample = metadata.bitsPerSample / 8
        var outputIndex = 0
        for (frame in 0 until framesRead) {
            val frameOffset = frame * metadata.blockAlign
            for (channel in 0 until info.channelCount) {
                val sampleOffset = frameOffset + channel * bytesPerSample
                output[outputIndex++] = decodeSample(bytes, sampleOffset)
            }
        }

        currentFrame += framesRead
        return framesRead
    }

    override fun close() {
        stream?.close()
        stream = null
    }

    private fun decodeSample(bytes: ByteArray, offset: Int): Float = when (metadata.encoding) {
        WavEncoding.PCM_INTEGER -> when (metadata.bitsPerSample) {
            8 -> ((bytes[offset].toInt() and 0xff) - 128) / 128f
            16 -> signed16(bytes, offset) / 32768f
            24 -> signed24(bytes, offset) / 8_388_608f
            32 -> signed32(bytes, offset) / 2_147_483_648f
            else -> error("Unsupported PCM bit depth: ${metadata.bitsPerSample}")
        }

        WavEncoding.IEEE_FLOAT -> when (metadata.bitsPerSample) {
            32 -> Float.fromBits(signed32(bytes, offset))
            64 -> Double.fromBits(signed64(bytes, offset)).toFloat()
            else -> error("Unsupported float bit depth: ${metadata.bitsPerSample}")
        }
    }

    companion object {
        private const val WAVE_FORMAT_PCM = 0x0001
        private const val WAVE_FORMAT_IEEE_FLOAT = 0x0003
        private const val WAVE_FORMAT_EXTENSIBLE = 0xfffe

        private fun parseHeader(raw: InputStream): WavMetadata {
            raw.use { source ->
                val input = CountingInput(source)
                val riff = input.readAscii(4)
                require(riff == "RIFF") {
                    if (riff == "RF64") "RF64 WAV is not supported yet" else "Not a RIFF WAV file"
                }
                input.readUInt32LE() // RIFF container size
                require(input.readAscii(4) == "WAVE") { "RIFF file is not WAVE audio" }

                var format: ParsedFormat? = null
                while (true) {
                    val chunkId = input.readAsciiOrNull(4) ?: error("WAV has no data chunk")
                    val chunkSize = input.readUInt32LE()
                    when (chunkId) {
                        "fmt " -> {
                            require(chunkSize in 16..65_536) { "Invalid fmt chunk size: $chunkSize" }
                            val bytes = input.readExact(chunkSize.toInt())
                            format = parseFormat(bytes)
                            if ((chunkSize and 1L) != 0L) input.skipFully(1)
                        }

                        "data" -> {
                            val parsed = requireNotNull(format) { "WAV data chunk appeared before fmt chunk" }
                            require(chunkSize != 0xffff_ffffL) { "RF64-sized data requires RF64 support" }
                            val totalFrames = chunkSize / parsed.blockAlign
                            require(totalFrames > 0) { "WAV data chunk contains no complete audio frames" }
                            return WavMetadata(
                                encoding = parsed.encoding,
                                bitsPerSample = parsed.bitsPerSample,
                                blockAlign = parsed.blockAlign,
                                dataSizeBytes = chunkSize,
                                dataOffsetBytes = input.position,
                                info = AudioStreamInfo(
                                    sampleRate = SampleRate(parsed.sampleRate),
                                    channelCount = parsed.channels,
                                    totalFrames = totalFrames,
                                ),
                            )
                        }

                        else -> {
                            input.skipFully(chunkSize)
                            if ((chunkSize and 1L) != 0L) input.skipFully(1)
                        }
                    }
                }
            }
        }

        private fun parseFormat(bytes: ByteArray): ParsedFormat {
            require(bytes.size >= 16)
            val rawTag = uint16LE(bytes, 0)
            val channels = uint16LE(bytes, 2)
            val sampleRateLong = uint32LE(bytes, 4)
            val blockAlign = uint16LE(bytes, 12)
            val bitsPerSample = uint16LE(bytes, 14)

            require(channels > 0) { "WAV must have at least one channel" }
            require(sampleRateLong in 1..Int.MAX_VALUE.toLong()) { "Invalid WAV sample rate: $sampleRateLong" }
            require(bitsPerSample > 0 && bitsPerSample % 8 == 0) { "Unsupported WAV bit depth: $bitsPerSample" }
            val bytesPerSample = bitsPerSample / 8
            require(blockAlign >= channels * bytesPerSample) { "Invalid WAV block alignment" }

            val actualTag = if (rawTag == WAVE_FORMAT_EXTENSIBLE) {
                require(bytes.size >= 40) { "WAVE_FORMAT_EXTENSIBLE fmt chunk is too short" }
                uint16LE(bytes, 24)
            } else {
                rawTag
            }

            val encoding = when (actualTag) {
                WAVE_FORMAT_PCM -> {
                    require(bitsPerSample in setOf(8, 16, 24, 32)) { "Unsupported PCM bit depth: $bitsPerSample" }
                    WavEncoding.PCM_INTEGER
                }

                WAVE_FORMAT_IEEE_FLOAT -> {
                    require(bitsPerSample == 32 || bitsPerSample == 64) { "Unsupported float bit depth: $bitsPerSample" }
                    WavEncoding.IEEE_FLOAT
                }

                else -> error("Unsupported WAV format tag: 0x${actualTag.toString(16)}")
            }

            return ParsedFormat(
                encoding = encoding,
                channels = channels,
                sampleRate = sampleRateLong.toInt(),
                blockAlign = blockAlign,
                bitsPerSample = bitsPerSample,
            )
        }

        private data class ParsedFormat(
            val encoding: WavEncoding,
            val channels: Int,
            val sampleRate: Int,
            val blockAlign: Int,
            val bitsPerSample: Int,
        )

        private fun signed16(bytes: ByteArray, offset: Int): Int {
            val value = (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)
            return if ((value and 0x8000) != 0) value or -0x10000 else value
        }

        private fun signed24(bytes: ByteArray, offset: Int): Int {
            val value = (bytes[offset].toInt() and 0xff) or
                ((bytes[offset + 1].toInt() and 0xff) shl 8) or
                ((bytes[offset + 2].toInt() and 0xff) shl 16)
            return if ((value and 0x800000) != 0) value or -0x1000000 else value
        }

        private fun signed32(bytes: ByteArray, offset: Int): Int =
            (bytes[offset].toInt() and 0xff) or
                ((bytes[offset + 1].toInt() and 0xff) shl 8) or
                ((bytes[offset + 2].toInt() and 0xff) shl 16) or
                (bytes[offset + 3].toInt() shl 24)

        private fun signed64(bytes: ByteArray, offset: Int): Long {
            var value = 0L
            for (index in 0 until 8) {
                value = value or ((bytes[offset + index].toLong() and 0xffL) shl (8 * index))
            }
            return value
        }

        private fun uint16LE(bytes: ByteArray, offset: Int): Int =
            (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

        private fun uint32LE(bytes: ByteArray, offset: Int): Long =
            (bytes[offset].toLong() and 0xffL) or
                ((bytes[offset + 1].toLong() and 0xffL) shl 8) or
                ((bytes[offset + 2].toLong() and 0xffL) shl 16) or
                ((bytes[offset + 3].toLong() and 0xffL) shl 24)
    }
}

private class CountingInput(private val source: InputStream) {
    var position: Long = 0
        private set

    fun readAscii(count: Int): String = readExact(count).toString(Charsets.US_ASCII)

    fun readAsciiOrNull(count: Int): String? {
        val first = source.read()
        if (first < 0) return null
        position++
        val bytes = ByteArray(count)
        bytes[0] = first.toByte()
        var filled = 1
        while (filled < count) {
            val read = source.read(bytes, filled, count - filled)
            if (read < 0) throw EOFException("Unexpected end of WAV")
            filled += read
            position += read
        }
        return bytes.toString(Charsets.US_ASCII)
    }

    fun readUInt32LE(): Long {
        val bytes = readExact(4)
        return (bytes[0].toLong() and 0xffL) or
            ((bytes[1].toLong() and 0xffL) shl 8) or
            ((bytes[2].toLong() and 0xffL) shl 16) or
            ((bytes[3].toLong() and 0xffL) shl 24)
    }

    fun readExact(count: Int): ByteArray {
        val bytes = ByteArray(count)
        var filled = 0
        while (filled < count) {
            val read = source.read(bytes, filled, count - filled)
            if (read < 0) throw EOFException("Unexpected end of WAV")
            filled += read
            position += read
        }
        return bytes
    }

    fun skipFully(count: Long) {
        source.skipFully(count)
        position += count
    }
}

private fun InputStream.skipFully(count: Long) {
    var remaining = count
    while (remaining > 0) {
        val skipped = skip(remaining)
        if (skipped > 0) {
            remaining -= skipped
        } else {
            if (read() < 0) throw EOFException("Unexpected end of stream while skipping")
            remaining--
        }
    }
}

private fun InputStream.readUpTo(buffer: ByteArray, count: Int): Int {
    var total = 0
    while (total < count) {
        val read = read(buffer, total, count - total)
        if (read < 0) break
        if (read == 0) continue
        total += read
    }
    return total
}
