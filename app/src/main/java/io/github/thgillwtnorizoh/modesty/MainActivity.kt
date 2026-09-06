package io.github.thgillwtnorizoh.modesty

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.thgillwtnorizoh.modesty.core.io.WavDecoder
import io.github.thgillwtnorizoh.modesty.core.io.WavEncoding
import io.github.thgillwtnorizoh.modesty.core.model.AudioClip
import io.github.thgillwtnorizoh.modesty.core.model.AudioProject
import io.github.thgillwtnorizoh.modesty.core.model.AudioSource
import io.github.thgillwtnorizoh.modesty.core.model.AudioTrack as ProjectTrack
import io.github.thgillwtnorizoh.modesty.core.model.SourceRange
import io.github.thgillwtnorizoh.modesty.core.playback.PlaybackState
import io.github.thgillwtnorizoh.modesty.core.waveform.InMemoryWaveformCache
import io.github.thgillwtnorizoh.modesty.core.waveform.WaveformPyramidBuilder
import io.github.thgillwtnorizoh.modesty.platform.playback.AndroidSingleClipPlaybackEngine
import io.github.thgillwtnorizoh.modesty.ui.WaveformView
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : Activity() {
    private lateinit var statusText: TextView
    private lateinit var metadataText: TextView
    private lateinit var waveformView: WaveformView
    private lateinit var playPauseButton: Button
    private lateinit var stopButton: Button
    private lateinit var timeText: TextView
    private lateinit var playbackEngine: AndroidSingleClipPlaybackEngine

    private val worker = Executors.newSingleThreadExecutor()
    private val loadGeneration = AtomicInteger(0)
    private val uiHandler = Handler(Looper.getMainLooper())

    private var selectedUri: String? = null
    private var playbackLoaded = false
    private var loadedTotalFrames = 0L
    private var loadedSampleRate = 48_000
    private var lastShownPlaybackError: String? = null

    private val progressTicker = object : Runnable {
        override fun run() {
            if (::playbackEngine.isInitialized && !isDestroyed) updatePlaybackUi()
            uiHandler.postDelayed(this, 33L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedUri = savedInstanceState?.getString(STATE_SELECTED_URI)
        playbackEngine = AndroidSingleClipPlaybackEngine { source ->
            WavDecoder {
                contentResolver.openInputStream(Uri.parse(source.location))
                    ?: error("Android could not reopen this document for playback")
            }
        }
        setContentView(buildContent())
        uiHandler.post(progressTicker)

        selectedUri?.let { loadWav(Uri.parse(it)) }
    }

    private fun buildContent(): ScrollView {
        val padding = dp(20)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(padding, padding, padding, padding)
        }

        root.addView(TextView(this).apply {
            text = "Modesty"
            textSize = 30f
            gravity = Gravity.CENTER
        })

        root.addView(TextView(this).apply {
            text = "Foundation brick 3\nWaveform has acquired vocal cords."
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(18))
        })

        root.addView(Button(this).apply {
            text = "Open WAV"
            setOnClickListener { chooseWav() }
        })

        statusText = TextView(this).apply {
            text = "Choose a WAV file to inspect and play."
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(8))
        }
        root.addView(statusText)

        metadataText = TextView(this).apply {
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(10))
        }
        root.addView(metadataText)

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        playPauseButton = Button(this).apply {
            text = "Play"
            isEnabled = false
            setOnClickListener { togglePlayback() }
        }
        stopButton = Button(this).apply {
            text = "Stop"
            isEnabled = false
            setOnClickListener {
                playbackEngine.stop()
                updatePlaybackUi()
            }
        }
        controls.addView(playPauseButton)
        controls.addView(stopButton)
        root.addView(controls)

        timeText = TextView(this).apply {
            text = "0:00.000 / 0:00.000"
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(10))
        }
        root.addView(timeText)

        waveformView = WaveformView(this).apply {
            setPadding(dp(4), dp(4), dp(4), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(320),
            )
            onSeekRequested = { frame ->
                if (playbackLoaded) {
                    playbackEngine.seekTo(frame)
                    updatePlaybackUi()
                }
            }
        }
        root.addView(waveformView)

        root.addView(TextView(this).apply {
            text = "Tap the waveform to seek. Brick 3 plays mono/stereo WAV at its native sample rate.\nEditing is still deliberately locked outside."
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
        })

        return ScrollView(this).apply {
            addView(
                root,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
    }

    private fun togglePlayback() {
        if (!playbackLoaded) return
        when (playbackEngine.state) {
            PlaybackState.PLAYING -> playbackEngine.pause()
            PlaybackState.PAUSED,
            PlaybackState.STOPPED,
            -> {
                if (playbackEngine.playheadFrame >= loadedTotalFrames) {
                    playbackEngine.seekTo(0)
                }
                playbackEngine.play()
            }
        }
        updatePlaybackUi()
    }

    private fun chooseWav() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf("audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave", "application/octet-stream"),
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_OPEN_WAV)
    }

    @Deprecated("Legacy Activity callback keeps the foundation dependency-free")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_OPEN_WAV || resultCode != RESULT_OK) return
        val uri = data?.data ?: return

        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Some providers grant only temporary access. The current selection still remains usable.
        }

        selectedUri = uri.toString()
        loadWav(uri)
    }

    private fun loadWav(uri: Uri) {
        val generation = loadGeneration.incrementAndGet()
        playbackEngine.stop()
        playbackLoaded = false
        loadedTotalFrames = 0
        lastShownPlaybackError = null
        statusText.text = "Reading WAV and building waveform…"
        metadataText.text = ""
        waveformView.clearWaveform()
        updatePlaybackUi()

        worker.execute {
            try {
                val decoder = WavDecoder {
                    contentResolver.openInputStream(uri)
                        ?: error("Android could not open this document")
                }
                val metadata = decoder.metadata
                val pyramid = try {
                    WaveformPyramidBuilder.build(decoder)
                } finally {
                    decoder.close()
                }

                val sourceId = uri.toString()
                val cache = InMemoryWaveformCache().put(sourceId, pyramid)
                val displayName = queryDisplayName(uri)
                val source = AudioSource(
                    id = sourceId,
                    location = uri.toString(),
                    sampleRate = metadata.info.sampleRate,
                    channelCount = metadata.info.channelCount,
                    totalFrames = metadata.info.totalFrames,
                )
                val project = AudioProject(
                    id = "brick3-project",
                    title = displayName,
                    timelineRate = source.sampleRate,
                    sources = mapOf(source.id to source),
                    tracks = listOf(
                        ProjectTrack(
                            id = "track-1",
                            name = displayName,
                            clips = listOf(
                                AudioClip(
                                    id = "clip-1",
                                    sourceId = source.id,
                                    sourceRange = SourceRange(0, source.totalFrames),
                                    timelineStartFrame = 0,
                                ),
                            ),
                        ),
                    ),
                )
                playbackEngine.load(project)

                val details = buildString {
                    append(displayName)
                    append('\n')
                    append(metadata.info.sampleRate.hz)
                    append(" Hz • ")
                    append(metadata.info.channelCount)
                    append(if (metadata.info.channelCount == 1) " channel • " else " channels • ")
                    append(metadata.bitsPerSample)
                    append("-bit ")
                    append(if (metadata.encoding == WavEncoding.PCM_INTEGER) "PCM" else "float")
                    append('\n')
                    append(formatDuration(metadata.info.totalFrames, metadata.info.sampleRate.hz))
                    append(" • ")
                    append(String.format(Locale.US, "%.2f MiB audio data", metadata.dataSizeBytes / 1048576.0))
                }

                runOnUiThread {
                    if (generation != loadGeneration.get() || isDestroyed) return@runOnUiThread
                    playbackLoaded = true
                    loadedTotalFrames = pyramid.totalFrames
                    loadedSampleRate = metadata.info.sampleRate.hz
                    statusText.text = "Waveform ready. Playback armed."
                    metadataText.text = details
                    waveformView.setWaveform(
                        cache = cache,
                        sourceId = sourceId,
                        totalFrames = pyramid.totalFrames,
                        channelCount = pyramid.channelCount,
                    )
                    updatePlaybackUi()
                }
            } catch (error: Throwable) {
                runOnUiThread {
                    if (generation != loadGeneration.get() || isDestroyed) return@runOnUiThread
                    playbackLoaded = false
                    statusText.text = "Could not read/play this WAV."
                    metadataText.text = error.message ?: error.javaClass.simpleName
                    waveformView.clearWaveform()
                    updatePlaybackUi()
                }
            }
        }
    }

    private fun updatePlaybackUi() {
        if (!::playPauseButton.isInitialized) return

        playPauseButton.isEnabled = playbackLoaded
        playPauseButton.text = if (playbackEngine.state == PlaybackState.PLAYING) "Pause" else "Play"
        stopButton.isEnabled = playbackLoaded &&
            (playbackEngine.state != PlaybackState.STOPPED || playbackEngine.playheadFrame > 0)

        val frame = playbackEngine.playheadFrame.coerceIn(0L, loadedTotalFrames.coerceAtLeast(0L))
        waveformView.setPlayheadFrame(frame)
        timeText.text = "${formatDuration(frame, loadedSampleRate)} / ${formatDuration(loadedTotalFrames, loadedSampleRate)}"

        val error = playbackEngine.lastError
        if (error != null && error != lastShownPlaybackError) {
            lastShownPlaybackError = error
            statusText.text = "Playback error: $error"
        }
    }

    private fun queryDisplayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index)
            }
        }
        return uri.lastPathSegment ?: "Selected WAV"
    }

    private fun formatDuration(frames: Long, sampleRate: Int): String {
        if (sampleRate <= 0) return "0:00.000"
        val seconds = frames.coerceAtLeast(0).toDouble() / sampleRate
        val minutesPart = (seconds / 60.0).toInt()
        val secondsPart = seconds - minutesPart * 60.0
        return String.format(Locale.US, "%d:%06.3f", minutesPart, secondsPart)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    override fun onStop() {
        if (::playbackEngine.isInitialized && playbackEngine.state == PlaybackState.PLAYING) {
            playbackEngine.pause()
        }
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        selectedUri?.let { outState.putString(STATE_SELECTED_URI, it) }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        loadGeneration.incrementAndGet()
        uiHandler.removeCallbacks(progressTicker)
        worker.shutdownNow()
        if (::playbackEngine.isInitialized) playbackEngine.close()
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_OPEN_WAV = 2001
        private const val STATE_SELECTED_URI = "selected_wav_uri"
    }
}
