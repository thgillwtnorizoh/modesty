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
import io.github.thgillwtnorizoh.modesty.core.editing.ProjectEditor
import io.github.thgillwtnorizoh.modesty.core.editing.TrimClip
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
    private lateinit var trimButton: Button
    private lateinit var undoButton: Button
    private lateinit var timeText: TextView
    private lateinit var selectionText: TextView
    private lateinit var playbackEngine: AndroidSingleClipPlaybackEngine

    private val worker = Executors.newSingleThreadExecutor()
    private val loadGeneration = AtomicInteger(0)
    private val uiHandler = Handler(Looper.getMainLooper())

    private var selectedUri: String? = null
    private var projectEditor: ProjectEditor? = null
    private var playbackLoaded = false
    private var loadedSampleRate = 48_000
    private var clipTimelineStartFrame = 0L
    private var clipTimelineEndFrameExclusive = 0L
    private var selectionSourceStartFrame: Long? = null
    private var selectionSourceEndFrameExclusive: Long? = null
    private var restoredClipStartFrame: Long? = null
    private var restoredClipEndFrameExclusive: Long? = null
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
        if (savedInstanceState?.containsKey(STATE_CLIP_SOURCE_START) == true) {
            restoredClipStartFrame = savedInstanceState.getLong(STATE_CLIP_SOURCE_START)
        }
        if (savedInstanceState?.containsKey(STATE_CLIP_SOURCE_END) == true) {
            restoredClipEndFrameExclusive = savedInstanceState.getLong(STATE_CLIP_SOURCE_END)
        }

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
            text = "Foundation brick 4\nThe waveform has acquired scissors."
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(18))
        })

        root.addView(Button(this).apply {
            text = "Open WAV"
            setOnClickListener { chooseWav() }
        })

        statusText = TextView(this).apply {
            text = "Choose a WAV file to inspect, play, and trim."
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

        val playbackControls = LinearLayout(this).apply {
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
        playbackControls.addView(playPauseButton)
        playbackControls.addView(stopButton)
        root.addView(playbackControls)

        timeText = TextView(this).apply {
            text = "0:00.000 / 0:00.000"
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(8))
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
            onSelectionChanged = { start, end ->
                selectionSourceStartFrame = start
                selectionSourceEndFrameExclusive = end
                updateSelectionUi()
            }
        }
        root.addView(waveformView)

        selectionText = TextView(this).apply {
            text = "Drag across the waveform to select a range. Tap to seek."
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(4))
        }
        root.addView(selectionText)

        val editControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        trimButton = Button(this).apply {
            text = "Trim"
            isEnabled = false
            setOnClickListener { trimToSelection() }
        }
        undoButton = Button(this).apply {
            text = "Undo"
            isEnabled = false
            setOnClickListener { undoEdit() }
        }
        editControls.addView(trimButton)
        editControls.addView(undoButton)
        root.addView(editControls)

        root.addView(TextView(this).apply {
            text = "Drag selects. Trim changes clip metadata only; the source WAV is never rewritten.\nUndo restores the previous clip range."
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
                if (playbackEngine.playheadFrame >= clipTimelineEndFrameExclusive) {
                    playbackEngine.seekTo(clipTimelineStartFrame)
                }
                playbackEngine.play()
            }
        }
        updatePlaybackUi()
    }

    private fun trimToSelection() {
        val editor = projectEditor ?: return
        val start = selectionSourceStartFrame ?: return
        val end = selectionSourceEndFrameExclusive ?: return
        val clip = editor.project.tracks.single().clips.single()
        if (end <= start || (start == clip.sourceRange.startFrame && end == clip.sourceRange.endFrameExclusive)) return

        playbackEngine.stop()
        try {
            editor.apply(
                TrimClip(
                    trackId = TRACK_ID,
                    clipId = CLIP_ID,
                    newSourceStartFrame = start,
                    newSourceEndFrameExclusive = end,
                ),
            )
            val duration = end - start
            bindEditorProject(
                "Trimmed nondestructively to ${formatDuration(duration, loadedSampleRate)}. Source WAV untouched.",
            )
        } catch (error: Throwable) {
            statusText.text = "Trim failed: ${error.message ?: error.javaClass.simpleName}"
        }
    }

    private fun undoEdit() {
        val editor = projectEditor ?: return
        if (!editor.undo()) return
        playbackEngine.stop()
        bindEditorProject("Undo restored the previous clip range.")
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
        restoredClipStartFrame = null
        restoredClipEndFrameExclusive = null
        loadWav(uri)
    }

    private fun loadWav(uri: Uri) {
        val generation = loadGeneration.incrementAndGet()
        playbackEngine.stop()
        projectEditor = null
        playbackLoaded = false
        clipTimelineStartFrame = 0
        clipTimelineEndFrameExclusive = 0
        selectionSourceStartFrame = null
        selectionSourceEndFrameExclusive = null
        lastShownPlaybackError = null
        statusText.text = "Reading WAV and building waveform…"
        metadataText.text = ""
        waveformView.clearWaveform()
        updateSelectionUi()
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
                    id = "brick4-project",
                    title = displayName,
                    timelineRate = source.sampleRate,
                    sources = mapOf(source.id to source),
                    tracks = listOf(
                        ProjectTrack(
                            id = TRACK_ID,
                            name = displayName,
                            clips = listOf(
                                AudioClip(
                                    id = CLIP_ID,
                                    sourceId = source.id,
                                    sourceRange = SourceRange(0, source.totalFrames),
                                    timelineStartFrame = 0,
                                ),
                            ),
                        ),
                    ),
                )

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

                    loadedSampleRate = metadata.info.sampleRate.hz
                    metadataText.text = details
                    waveformView.setWaveform(
                        cache = cache,
                        sourceId = sourceId,
                        totalFrames = pyramid.totalFrames,
                        channelCount = pyramid.channelCount,
                    )

                    val editor = ProjectEditor(project)
                    val restoredStart = restoredClipStartFrame
                    val restoredEnd = restoredClipEndFrameExclusive
                    if (
                        restoredStart != null &&
                        restoredEnd != null &&
                        restoredStart >= 0 &&
                        restoredEnd <= source.totalFrames &&
                        restoredEnd > restoredStart &&
                        (restoredStart != 0L || restoredEnd != source.totalFrames)
                    ) {
                        editor.apply(TrimClip(TRACK_ID, CLIP_ID, restoredStart, restoredEnd))
                    }
                    restoredClipStartFrame = null
                    restoredClipEndFrameExclusive = null
                    projectEditor = editor
                    bindEditorProject("Waveform ready. Playback and nondestructive trim armed.")
                }
            } catch (error: Throwable) {
                runOnUiThread {
                    if (generation != loadGeneration.get() || isDestroyed) return@runOnUiThread
                    projectEditor = null
                    playbackLoaded = false
                    statusText.text = "Could not read/play this WAV."
                    metadataText.text = error.message ?: error.javaClass.simpleName
                    waveformView.clearWaveform()
                    updateSelectionUi()
                    updatePlaybackUi()
                }
            }
        }
    }

    private fun bindEditorProject(statusMessage: String) {
        val editor = projectEditor ?: return
        val project = editor.project
        val clip = project.tracks.single().clips.single()
        val clipDuration = project.clipTimelineDurationFrames(clip)

        selectionSourceStartFrame = null
        selectionSourceEndFrameExclusive = null
        clipTimelineStartFrame = clip.timelineStartFrame
        clipTimelineEndFrameExclusive = clip.timelineStartFrame + clipDuration
        lastShownPlaybackError = null

        playbackEngine.load(project)
        playbackLoaded = true
        waveformView.setClipWindow(
            sourceStartFrame = clip.sourceRange.startFrame,
            sourceEndFrameExclusive = clip.sourceRange.endFrameExclusive,
            timelineStartFrame = clipTimelineStartFrame,
            timelineEndFrameExclusive = clipTimelineEndFrameExclusive,
        )
        statusText.text = statusMessage
        undoButton.isEnabled = editor.canUndo
        updateSelectionUi()
        updatePlaybackUi()
    }

    private fun updateSelectionUi() {
        if (!::selectionText.isInitialized || !::trimButton.isInitialized) return
        val editor = projectEditor
        val start = selectionSourceStartFrame
        val end = selectionSourceEndFrameExclusive

        if (editor == null || start == null || end == null || end <= start) {
            selectionText.text = "Drag across the waveform to select a range. Tap to seek."
            trimButton.isEnabled = false
            if (::undoButton.isInitialized) undoButton.isEnabled = editor?.canUndo == true
            return
        }

        val clip = editor.project.tracks.single().clips.single()
        val relativeStart = start - clip.sourceRange.startFrame
        val relativeEnd = end - clip.sourceRange.startFrame
        val duration = end - start
        selectionText.text = buildString {
            append("Selection ")
            append(formatDuration(relativeStart, loadedSampleRate))
            append(" → ")
            append(formatDuration(relativeEnd, loadedSampleRate))
            append("  (")
            append(formatDuration(duration, loadedSampleRate))
            append(')')
        }
        trimButton.isEnabled = playbackLoaded &&
            (start != clip.sourceRange.startFrame || end != clip.sourceRange.endFrameExclusive)
        undoButton.isEnabled = editor.canUndo
    }

    private fun updatePlaybackUi() {
        if (!::playPauseButton.isInitialized) return

        playPauseButton.isEnabled = playbackLoaded
        playPauseButton.text = if (playbackEngine.state == PlaybackState.PLAYING) "Pause" else "Play"
        stopButton.isEnabled = playbackLoaded &&
            (playbackEngine.state != PlaybackState.STOPPED || playbackEngine.playheadFrame > clipTimelineStartFrame)

        if (!playbackLoaded || clipTimelineEndFrameExclusive <= clipTimelineStartFrame) {
            timeText.text = "0:00.000 / 0:00.000"
            return
        }

        val frame = playbackEngine.playheadFrame.coerceIn(clipTimelineStartFrame, clipTimelineEndFrameExclusive)
        waveformView.setPlayheadFrame(frame)
        val relativeFrame = frame - clipTimelineStartFrame
        val durationFrames = clipTimelineEndFrameExclusive - clipTimelineStartFrame
        timeText.text = "${formatDuration(relativeFrame, loadedSampleRate)} / ${formatDuration(durationFrames, loadedSampleRate)}"

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
        projectEditor?.project?.tracks?.singleOrNull()?.clips?.singleOrNull()?.let { clip ->
            outState.putLong(STATE_CLIP_SOURCE_START, clip.sourceRange.startFrame)
            outState.putLong(STATE_CLIP_SOURCE_END, clip.sourceRange.endFrameExclusive)
        }
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
        private const val STATE_CLIP_SOURCE_START = "clip_source_start"
        private const val STATE_CLIP_SOURCE_END = "clip_source_end"
        private const val TRACK_ID = "track-1"
        private const val CLIP_ID = "clip-1"
    }
}
