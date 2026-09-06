package io.github.thgillwtnorizoh.modesty.core.render

import io.github.thgillwtnorizoh.modesty.core.dsp.GainProcessor
import io.github.thgillwtnorizoh.modesty.core.io.AudioDecoder
import io.github.thgillwtnorizoh.modesty.core.io.AudioEncoder
import io.github.thgillwtnorizoh.modesty.core.io.AudioStreamInfo
import io.github.thgillwtnorizoh.modesty.core.model.AudioProject
import io.github.thgillwtnorizoh.modesty.core.model.AudioSource
import io.github.thgillwtnorizoh.modesty.core.playback.TimelinePlaybackPlan

/**
 * Offline renderer for the current single-track, non-overlapping project model.
 *
 * It deliberately mirrors the same clip/gap/gain semantics used by playback while remaining
 * independent from Android and from any particular file format. Encoders decide how rendered
 * float PCM is packaged.
 */
class TimelineRenderer(
    project: AudioProject,
    private val decoderFactory: (AudioSource) -> AudioDecoder,
) {
    private val plan = TimelinePlaybackPlan.from(project)

    val outputInfo: AudioStreamInfo = AudioStreamInfo(
        sampleRate = plan.sampleRate,
        channelCount = plan.channelCount,
        totalFrames = plan.timelineEndFrameExclusive - plan.timelineStartFrame,
    )

    fun render(
        encoder: AudioEncoder,
        onProgress: ((Float) -> Unit)? = null,
    ) {
        val bufferFrames = 4_096
        val channelCount = plan.channelCount
        val samples = FloatArray(bufferFrames * channelCount)
        val totalFrames = outputInfo.totalFrames
        var renderedFrames = 0L
        var cursor = plan.timelineStartFrame

        while (cursor < plan.timelineEndFrameExclusive) {
            check(!Thread.currentThread().isInterrupted) { "Export was cancelled" }

            val segment = plan.segmentAt(cursor)
            if (segment == null) {
                val nextStart = plan.nextSegmentStartAfter(cursor) ?: plan.timelineEndFrameExclusive
                val gapEnd = minOf(nextStart, plan.timelineEndFrameExclusive)
                val frames = minOf(bufferFrames.toLong(), gapEnd - cursor).toInt()
                require(frames > 0) { "Renderer could not advance through timeline gap" }

                java.util.Arrays.fill(samples, 0, frames * channelCount, 0f)
                encoder.writeInterleaved(samples, frames)
                cursor += frames
                renderedFrames += frames
                onProgress?.invoke((renderedFrames.toDouble() / totalFrames).toFloat().coerceIn(0f, 1f))
                continue
            }

            val segmentEnd = segment.timelineEndFrameExclusive
            decoderFactory(segment.source).use { decoder ->
                require(decoder.info.sampleRate == plan.sampleRate) { "Decoder sample rate changed during export" }
                require(decoder.info.channelCount == plan.channelCount) { "Decoder channel layout changed during export" }
                require(decoder.info.totalFrames >= segment.source.totalFrames) { "Decoder source became shorter during export" }

                decoder.seekToSourceFrame(segment.sourceFrameForTimeline(plan.project, cursor))
                val gainProcessor = GainProcessor(segment.clip.gain)

                while (cursor < segmentEnd) {
                    check(!Thread.currentThread().isInterrupted) { "Export was cancelled" }
                    val framesRequested = minOf(bufferFrames.toLong(), segmentEnd - cursor).toInt()
                    val framesRead = decoder.readInterleaved(samples, framesRequested)
                    if (framesRead <= 0) error("Decoder ended before clip ${segment.clip.id} during export")

                    val sampleCount = framesRead * channelCount
                    for (index in 0 until sampleCount) {
                        if (!samples[index].isFinite()) samples[index] = 0f
                    }
                    gainProcessor.process(samples, samples, framesRead, channelCount)
                    for (index in 0 until sampleCount) {
                        samples[index] = if (samples[index].isFinite()) {
                            samples[index].coerceIn(-1f, 1f)
                        } else {
                            0f
                        }
                    }

                    encoder.writeInterleaved(samples, framesRead)
                    cursor += framesRead
                    renderedFrames += framesRead
                    onProgress?.invoke((renderedFrames.toDouble() / totalFrames).toFloat().coerceIn(0f, 1f))
                }
            }
        }

        require(renderedFrames == totalFrames) {
            "Renderer produced $renderedFrames frames, expected $totalFrames"
        }
        encoder.finish()
        onProgress?.invoke(1f)
    }
}
