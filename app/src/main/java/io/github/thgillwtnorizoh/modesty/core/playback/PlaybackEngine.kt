package io.github.thgillwtnorizoh.modesty.core.playback

import io.github.thgillwtnorizoh.modesty.core.model.AudioProject

enum class PlaybackState {
    STOPPED,
    PLAYING,
    PAUSED,
}

interface PlaybackEngine : AutoCloseable {
    val state: PlaybackState
    val playheadFrame: Long
    val lastError: String?

    fun load(project: AudioProject)
    fun play(startFrame: Long = playheadFrame, endFrameExclusive: Long? = null)
    fun pause()
    fun seekTo(frame: Long)
    fun stop()
}
