package io.github.thgillwtnorizoh.modesty.core.waveform

import io.github.thgillwtnorizoh.modesty.core.io.AudioDecoder
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class WaveformLevel(
    val framesPerBucket: Long,
    val channels: List<List<WaveformBucket>>,
)

data class WaveformPyramid(
    val totalFrames: Long,
    val channelCount: Int,
    val levels: List<WaveformLevel>,
) {
    init {
        require(totalFrames > 0)
        require(channelCount > 0)
        require(levels.isNotEmpty())
        require(levels.all { it.channels.size == channelCount })
    }
}

object WaveformPyramidBuilder {
    fun build(
        decoder: AudioDecoder,
        baseBucketFrames: Int = 256,
        readBlockFrames: Int = 4096,
    ): WaveformPyramid {
        require(baseBucketFrames > 0)
        require(readBlockFrames > 0)

        decoder.seekToSourceFrame(0)
        val channelCount = decoder.info.channelCount
        val buckets = List(channelCount) { mutableListOf<WaveformBucket>() }
        val mins = FloatArray(channelCount) { Float.POSITIVE_INFINITY }
        val maxs = FloatArray(channelCount) { Float.NEGATIVE_INFINITY }
        val squareSums = DoubleArray(channelCount)
        var framesInBucket = 0

        fun flushBucket() {
            if (framesInBucket == 0) return
            for (channel in 0 until channelCount) {
                buckets[channel].add(
                    WaveformBucket(
                        min = mins[channel],
                        max = maxs[channel],
                        rms = sqrt(squareSums[channel] / framesInBucket).toFloat(),
                        frameCount = framesInBucket.toLong(),
                    ),
                )
                mins[channel] = Float.POSITIVE_INFINITY
                maxs[channel] = Float.NEGATIVE_INFINITY
                squareSums[channel] = 0.0
            }
            framesInBucket = 0
        }

        val readBuffer = FloatArray(readBlockFrames * channelCount)
        while (true) {
            val framesRead = decoder.readInterleaved(readBuffer, readBlockFrames)
            if (framesRead <= 0) break
            for (frame in 0 until framesRead) {
                val frameOffset = frame * channelCount
                for (channel in 0 until channelCount) {
                    val sample = readBuffer[frameOffset + channel]
                    mins[channel] = min(mins[channel], sample)
                    maxs[channel] = max(maxs[channel], sample)
                    squareSums[channel] += sample.toDouble() * sample.toDouble()
                }
                framesInBucket++
                if (framesInBucket == baseBucketFrames) flushBucket()
            }
        }
        flushBucket()

        require(buckets.firstOrNull()?.isNotEmpty() == true) { "Decoder produced no audio samples" }

        val levels = mutableListOf(
            WaveformLevel(
                framesPerBucket = baseBucketFrames.toLong(),
                channels = buckets.map { it.toList() },
            ),
        )

        while (levels.last().channels.maxOf { it.size } > 1) {
            val previous = levels.last()
            val coarserChannels = previous.channels.map { channel ->
                buildList((channel.size + 1) / 2) {
                    var index = 0
                    while (index < channel.size) {
                        val first = channel[index]
                        val second = channel.getOrNull(index + 1)
                        add(if (second == null) first else combine(first, second))
                        index += 2
                    }
                }
            }
            levels.add(
                WaveformLevel(
                    framesPerBucket = previous.framesPerBucket * 2,
                    channels = coarserChannels,
                ),
            )
        }

        return WaveformPyramid(
            totalFrames = decoder.info.totalFrames,
            channelCount = channelCount,
            levels = levels,
        )
    }

    private fun combine(a: WaveformBucket, b: WaveformBucket): WaveformBucket {
        val frameCount = a.frameCount + b.frameCount
        val squareMean = (
            a.rms.toDouble() * a.rms * a.frameCount +
                b.rms.toDouble() * b.rms * b.frameCount
            ) / frameCount
        return WaveformBucket(
            min = min(a.min, b.min),
            max = max(a.max, b.max),
            rms = sqrt(squareMean).toFloat(),
            frameCount = frameCount,
        )
    }
}

class InMemoryWaveformCache : WaveformCache {
    private val pyramids = linkedMapOf<String, WaveformPyramid>()

    fun put(sourceId: String, pyramid: WaveformPyramid): InMemoryWaveformCache {
        require(sourceId.isNotBlank())
        pyramids[sourceId] = pyramid
        return this
    }

    override fun read(
        sourceId: String,
        channel: Int,
        startSourceFrame: Long,
        endSourceFrameExclusive: Long,
        bucketCount: Int,
    ): List<WaveformBucket> {
        val pyramid = requireNotNull(pyramids[sourceId]) { "No waveform cached for $sourceId" }
        require(channel in 0 until pyramid.channelCount)
        require(startSourceFrame >= 0 && endSourceFrameExclusive > startSourceFrame)
        require(endSourceFrameExclusive <= pyramid.totalFrames)
        require(bucketCount > 0)

        val targetFramesPerBucket = (endSourceFrameExclusive - startSourceFrame).toDouble() / bucketCount
        val level = pyramid.levels.minByOrNull { candidate ->
            abs(ln(candidate.framesPerBucket.toDouble() / targetFramesPerBucket.coerceAtLeast(1.0)))
        } ?: pyramid.levels.first()

        val source = level.channels[channel]
        val startIndex = (startSourceFrame / level.framesPerBucket).toInt().coerceIn(0, source.lastIndex)
        val endIndexExclusive = ((endSourceFrameExclusive + level.framesPerBucket - 1) / level.framesPerBucket)
            .toInt()
            .coerceIn(startIndex + 1, source.size)
        return source.subList(startIndex, endIndexExclusive)
    }

    override fun invalidate(sourceId: String) {
        pyramids.remove(sourceId)
    }
}
