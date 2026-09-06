package io.github.thgillwtnorizoh.modesty.platform.playback

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import io.github.thgillwtnorizoh.modesty.core.io.AudioDecoder
import io.github.thgillwtnorizoh.modesty.core.model.AudioProject
import io.github.thgillwtnorizoh.modesty.core.model.AudioSource
import io.github.thgillwtnorizoh.modesty.core.playback.PlaybackEngine
import io.github.thgillwtnorizoh.modesty.core.playback.PlaybackState
import io.github.thgillwtnorizoh.modesty.core.playback.SingleClipPlaybackPlan
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Brick #3 reference playback backend.
 *
 * This intentionally uses Android AudioTrack and supports one mono/stereo clip at its native
 * sample rate. It exists to prove Modesty's playback clock, pause/seek behaviour, and project ->
 * source time mapping before the later mixer/resampler/Oboe backend arrives.
 */
class AndroidSingleClipPlaybackEngine(
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
    private var plan: SingleClipPlaybackPlan? = null

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
        Thread(runnable, "Modesty-Brick3-Playback").apply { isDaemon = true }
    }

    override fun load(project: AudioProject) {
        check(!closed) { "Playback engine is closed" }
        cancelActiveOutput()
        val newPlan = SingleClipPlaybackPlan.from(project)
        require(newPlan.source.channelCount in 1..2) {
            "Brick 3 playback currently supports mono or stereo WAV files"
        }
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

        worker.execute {
            stream(localPlan, start, end, token)
        }
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

        if (resume && target < localPlan.timelineEndFrameExclusive) {
            play(target)
        }
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
        plan: SingleClipPlaybackPlan,
        startFrame: Long,
        endFrameExclusive: Long,
        token: Int,
    ) {
        var output: AudioTrack? = null
        var decoder: AudioDecoder? = null
        try {
            if (!isCurrent(token)) return

            decoder = decoderFactory(plan.source)
            require(decoder.info.sampleRate == plan.source.sampleRate) { "Decoder sample rate changed" }
            require(decoder.info.channelCount == plan.source.channelCount) { "Decoder channel count changed" }
            require(decoder.info.totalFrames >= plan.source.totalFrames) { "Decoder source became shorter" }

            decoder.seekToSourceFrame(plan.sourceFrameForTimeline(startFrame))
            output = createAudioTrack(plan.source)
            if (!isCurrent(token)) return

            activeTrack = output
            activeBaseFrame = startFrame
            activeEndFrame = endFrameExclusive
            output.play()

            val channelCount = plan.source.channelCount
            val bufferFrames = 2_048
            val samples = FloatArray(bufferFrames * channelCount)
            var timelineCursor = startFrame
            var submittedFrames = 0L

            while (isCurrent(token) && state == PlaybackState.PLAYING && timelineCursor < endFrameExclusive) {
                val framesRequested = minOf(
                    bufferFrames.toLong(),
                    endFrameExclusive - timelineCursor,
                ).toInt()
                val framesRead = decoder.readInterleaved(samples, framesRequested)
                if (framesRead <= 0) break

                val samplesToWrite = framesRead * channelCount
                for (index in 0 until samplesToWrite) {
                    if (!samples[index].isFinite()) samples[index] = 0f
                }

                var sampleOffset = 0
                while (
                    sampleOffset < samplesToWrite &&
                    isCurrent(token) &&
                    state == PlaybackState.PLAYING
                ) {
                    val written = output.write(
                        samples,
                        sampleOffset,
                        samplesToWrite - sampleOffset,
                        AudioTrack.WRITE_BLOCKING,
                    )
                    if (written < 0) error("AudioTrack write failed: $written")
                    if (written == 0) continue
                    sampleOffset += written
                }

                if (sampleOffset != samplesToWrite) break
                submittedFrames += framesRead
                timelineCursor += framesRead
                updatePlayheadFrom(output)
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
            runCatching { decoder?.close() }
            output?.let { track ->
                runCatching { track.pause() }
                runCatching { track.flush() }
                runCatching { track.release() }
            }
            if (activeTrack === output) activeTrack = null
        }
    }

    private fun createAudioTrack(source: AudioSource): AudioTrack {
        val channelMask = when (source.channelCount) {
            1 -> AudioFormat.CHANNEL_OUT_MONO
            2 -> AudioFormat.CHANNEL_OUT_STEREO
            else -> error("Brick 3 playback supports only mono/stereo output")
        }
        val encoding = AudioFormat.ENCODING_PCM_FLOAT
        val frameBytes = source.channelCount * Float.SIZE_BYTES
        val minimum = AudioTrack.getMinBufferSize(source.sampleRate.hz, channelMask, encoding)
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
                    .setSampleRate(source.sampleRate.hz)
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
        playheadFrame = (activeBaseFrame + renderedFrames)
            .coerceAtMost(activeEndFrame)
    }

    private fun isCurrent(token: Int): Boolean =
        !closed && generation.get() == token
}
