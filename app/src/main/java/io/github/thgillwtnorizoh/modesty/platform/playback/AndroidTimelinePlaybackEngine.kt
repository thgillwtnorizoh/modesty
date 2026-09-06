package io.github.thgillwtnorizoh.modesty.platform.playback

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import io.github.thgillwtnorizoh.modesty.core.io.AudioDecoder
import io.github.thgillwtnorizoh.modesty.core.model.AudioProject
import io.github.thgillwtnorizoh.modesty.core.model.AudioSource
import io.github.thgillwtnorizoh.modesty.core.playback.PlaybackEngine
import io.github.thgillwtnorizoh.modesty.core.playback.PlaybackState
import io.github.thgillwtnorizoh.modesty.core.playback.TimelinePlaybackPlan
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Brick #5 reference backend. It streams one non-overlapping timeline through one AudioTrack.
 * Gaps are written as real zero samples, so the hardware playback head remains the clock even
 * after split/delete operations create silence between clips.
 */
class AndroidTimelinePlaybackEngine(
    private val decoderFactory: (AudioSource) -> AudioDecoder,
) : PlaybackEngine {
    @Volatile
    override var state: PlaybackState = PlaybackState.STOPPED
        private set

    @Volatile
    override var playheadFrame: Long = 0
        private set

    @Volatile
    override var lastError: String? = null
        private set

    @Volatile
    private var plan: TimelinePlaybackPlan? = null

    @Volatile
    private var activeTrack: AudioTrack? = null

    @Volatile
    private var activeBaseFrame: Long = 0

    @Volatile
    private var activeEndFrame: Long = 0

    @Volatile
    private var closed = false

    private val generation = AtomicInteger(0)
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Modesty-Brick5-TimelinePlayback").apply { isDaemon = true }
    }

    override fun load(project: AudioProject) {
        check(!closed) { "Playback engine is closed" }
        cancelActiveOutput()
        val newPlan = TimelinePlaybackPlan.from(project)
        plan = newPlan
        playheadFrame = newPlan.timelineStartFrame
        lastError = null
        state = PlaybackState.STOPPED
    }

    override fun play(startFrame: Long, endFrameExclusive: Long?) {
        check(!closed) { "Playback engine is closed" }
        val localPlan = requireNotNull(plan) { "Load a project before playback" }
        val start = localPlan.clampTimelineFrame(startFrame)
        val end = minOf(
            endFrameExclusive ?: localPlan.timelineEndFrameExclusive,
            localPlan.timelineEndFrameExclusive,
        ).coerceAtLeast(start)

        if (start >= end) {
            cancelActiveOutput()
            playheadFrame = localPlan.timelineEndFrameExclusive
            state = PlaybackState.STOPPED
            return
        }

        val token = generation.incrementAndGet()
        activeTrack?.let { track ->
            runCatching { track.pause() }
            runCatching { track.flush() }
        }

        playheadFrame = start
        activeBaseFrame = start
        activeEndFrame = end
        lastError = null
        state = PlaybackState.PLAYING

        worker.execute { stream(localPlan, start, end, token) }
    }

    override fun pause() {
        if (state != PlaybackState.PLAYING) return

        activeTrack?.let { track ->
            runCatching { track.pause() }
            updatePlayheadFrom(track)
            runCatching { track.flush() }
        }
        generation.incrementAndGet()
        state = PlaybackState.PAUSED
    }

    override fun seekTo(frame: Long) {
        val localPlan = plan ?: return
        val target = localPlan.clampTimelineFrame(frame)
        val resume = state == PlaybackState.PLAYING

        if (resume) {
            pause()
        } else {
            generation.incrementAndGet()
            activeTrack?.let { track ->
                runCatching { track.pause() }
                runCatching { track.flush() }
            }
        }

        playheadFrame = target
        lastError = null

        if (resume && target < localPlan.timelineEndFrameExclusive) play(target)
    }

    override fun stop() {
        val localPlan = plan
        cancelActiveOutput()
        state = PlaybackState.STOPPED
        playheadFrame = localPlan?.timelineStartFrame ?: 0L
    }

    override fun close() {
        if (closed) return
        closed = true
        cancelActiveOutput()
        state = PlaybackState.STOPPED
        worker.shutdownNow()
    }

    private fun cancelActiveOutput() {
        generation.incrementAndGet()
        activeTrack?.let { track ->
            runCatching { track.pause() }
            runCatching { track.flush() }
        }
    }

    private fun stream(
        plan: TimelinePlaybackPlan,
        startFrame: Long,
        endFrameExclusive: Long,
        token: Int,
    ) {
        var output: AudioTrack? = null
        try {
            if (!isCurrent(token)) return

            output = createAudioTrack(plan)
            if (!isCurrent(token)) return

            activeTrack = output
            activeBaseFrame = startFrame
            activeEndFrame = endFrameExclusive
            output.play()

            val bufferFrames = 2_048
            val channelCount = plan.channelCount
            val samples = FloatArray(bufferFrames * channelCount)
            var cursor = startFrame
            var submittedFrames = 0L

            while (isCurrent(token) && state == PlaybackState.PLAYING && cursor < endFrameExclusive) {
                val segment = plan.segmentAt(cursor)
                if (segment == null) {
                    val nextStart = plan.nextSegmentStartAfter(cursor) ?: endFrameExclusive
                    val gapEnd = minOf(nextStart, endFrameExclusive)
                    val frames = minOf(bufferFrames.toLong(), gapEnd - cursor).toInt()
                    if (frames <= 0) {
                        cursor = gapEnd
                        continue
                    }
                    java.util.Arrays.fill(samples, 0, frames * channelCount, 0f)
                    writeFully(output, samples, frames * channelCount, token)
                    cursor += frames
                    submittedFrames += frames
                    updatePlayheadFrom(output)
                    continue
                }

                val segmentEnd = minOf(segment.timelineEndFrameExclusive, endFrameExclusive)
                var decoder: AudioDecoder? = null
                try {
                    decoder = decoderFactory(segment.source)
                    require(decoder.info.sampleRate == plan.sampleRate) { "Decoder sample rate changed" }
                    require(decoder.info.channelCount == plan.channelCount) { "Decoder channel count changed" }
                    require(decoder.info.totalFrames >= segment.source.totalFrames) { "Decoder source became shorter" }
                    decoder.seekToSourceFrame(segment.sourceFrameForTimeline(plan.project, cursor))

                    while (
                        isCurrent(token) &&
                        state == PlaybackState.PLAYING &&
                        cursor < segmentEnd
                    ) {
                        val framesRequested = minOf(bufferFrames.toLong(), segmentEnd - cursor).toInt()
                        val framesRead = decoder.readInterleaved(samples, framesRequested)
                        if (framesRead <= 0) error("Decoder ended before clip ${segment.clip.id}")

                        val sampleCount = framesRead * channelCount
                        for (index in 0 until sampleCount) {
                            if (!samples[index].isFinite()) samples[index] = 0f
                        }
                        writeFully(output, samples, sampleCount, token)
                        cursor += framesRead
                        submittedFrames += framesRead
                        updatePlayheadFrom(output)
                    }
                } finally {
                    runCatching { decoder?.close() }
                }
            }

            while (isCurrent(token) && state == PlaybackState.PLAYING) {
                updatePlayheadFrom(output)
                val playedFrames = output.playbackHeadPosition.toLong() and 0xffff_ffffL
                if (playedFrames >= submittedFrames) break
                Thread.sleep(8)
            }

            if (isCurrent(token) && state == PlaybackState.PLAYING) {
                playheadFrame = (startFrame + submittedFrames).coerceAtMost(endFrameExclusive)
                state = PlaybackState.STOPPED
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (error: Throwable) {
            if (isCurrent(token) && !closed) {
                lastError = error.message ?: error.javaClass.simpleName
                state = PlaybackState.STOPPED
            }
        } finally {
            output?.let { track ->
                runCatching { track.pause() }
                runCatching { track.flush() }
                runCatching { track.release() }
            }
            if (activeTrack === output) activeTrack = null
        }
    }

    private fun writeFully(
        output: AudioTrack,
        samples: FloatArray,
        sampleCount: Int,
        token: Int,
    ) {
        var offset = 0
        while (offset < sampleCount && isCurrent(token) && state == PlaybackState.PLAYING) {
            val written = output.write(
                samples,
                offset,
                sampleCount - offset,
                AudioTrack.WRITE_BLOCKING,
            )
            if (written < 0) error("AudioTrack write failed: $written")
            if (written == 0) continue
            offset += written
        }
        if (offset != sampleCount && isCurrent(token) && state == PlaybackState.PLAYING) {
            error("AudioTrack write was interrupted")
        }
    }

    private fun createAudioTrack(plan: TimelinePlaybackPlan): AudioTrack {
        val channelMask = when (plan.channelCount) {
            1 -> AudioFormat.CHANNEL_OUT_MONO
            2 -> AudioFormat.CHANNEL_OUT_STEREO
            else -> error("Brick 5 playback supports only mono/stereo output")
        }
        val encoding = AudioFormat.ENCODING_PCM_FLOAT
        val frameBytes = plan.channelCount * Float.SIZE_BYTES
        val minimum = AudioTrack.getMinBufferSize(plan.sampleRate.hz, channelMask, encoding)
        val desired = 4_096 * frameBytes
        val rawSize = if (minimum > 0) maxOf(minimum, desired) else desired
        val bufferSize = ((rawSize + frameBytes - 1) / frameBytes) * frameBytes

        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(encoding)
                    .setSampleRate(plan.sampleRate.hz)
                    .setChannelMask(channelMask)
                    .build(),
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also { track ->
                check(track.state == AudioTrack.STATE_INITIALIZED) { "Android could not initialize audio output" }
            }
    }

    private fun updatePlayheadFrom(track: AudioTrack) {
        val renderedFrames = track.playbackHeadPosition.toLong() and 0xffff_ffffL
        playheadFrame = (activeBaseFrame + renderedFrames).coerceAtMost(activeEndFrame)
    }

    private fun isCurrent(token: Int): Boolean = !closed && generation.get() == token
}
