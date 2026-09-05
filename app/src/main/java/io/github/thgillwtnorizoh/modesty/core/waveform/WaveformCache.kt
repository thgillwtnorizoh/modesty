package io.github.thgillwtnorizoh.modesty.core.waveform

data class WaveformBucket(
    val min: Float,
    val max: Float,
    val rms: Float,
    val frameCount: Long,
)

/**
 * Multi-resolution waveform access. Implementations may use memory, files, or a database,
 * but callers only ask for the visible source range and desired horizontal resolution.
 */
interface WaveformCache {
    fun read(
        sourceId: String,
        channel: Int,
        startSourceFrame: Long,
        endSourceFrameExclusive: Long,
        bucketCount: Int,
    ): List<WaveformBucket>

    fun invalidate(sourceId: String)
}
