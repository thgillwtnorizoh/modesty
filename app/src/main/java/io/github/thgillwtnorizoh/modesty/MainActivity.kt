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
import io.github.thgillwtnorizoh.modesty.core.editing.DeleteTimelineRange
import io.github.thgillwtnorizoh.modesty.core.editing.MoveClip
import io.github.thgillwtnorizoh.modesty.core.editing.ProjectEditor
import io.github.thgillwtnorizoh.modesty.core.editing.SplitTimelineRange
import io.github.thgillwtnorizoh.modesty.core.editing.TrimClip
import io.github.thgillwtnorizoh.modesty.core.editing.clipMoveBounds
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
import io.github.thgillwtnorizoh.modesty.platform.playback.AndroidTimelinePlaybackEngine
import io.github.thgillwtnorizoh.modesty.ui.TimelineWaveformClip
import io.github.thgillwtnorizoh.modesty.ui.WaveformView
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : Activity() {
    private lateinit var statusText: TextView
    private lateinit var metadataText: TextView
    private lateinit var waveformView: WaveformView
    private lateinit var playPauseButton: Button
    private lateinit var stopButton: Button
    private lateinit var trimButton: Button
    private lateinit var splitButton: Button
    private lateinit var deleteButton: Button
    private lateinit var undoButton: Button
    private lateinit var redoButton: Button
    private lateinit var timeText: TextView
    private lateinit var selectionText: TextView
    private lateinit var playbackEngine: AndroidTimelinePlaybackEngine

    private val worker = Executors.newSingleThreadExecutor()
    private val loadGeneration = AtomicInteger(0)
    private val uiHandler = Handler(Looper.getMainLooper())

    private var selectedUri: String? = null
    private var projectEditor: ProjectEditor? = null
    private var waveformCache: InMemoryWaveformCache? = null
    private var playbackLoaded = false
    private var loadedSampleRate = 48_000
    private var loadedChannelCount = 0
    private var loadedSourceTotalFrames = 0L
    private var timelineWindowStartFrame = 0L
    private var timelineWindowEndFrameExclusive = 0L
    private var selectionTimelineStartFrame: Long? = null
    private var selectionTimelineEndFrameExclusive: Long? = null
    private var lastShownPlaybackError: String? = null

    private var restoredClipIds: ArrayList<String>? = null
    private var restoredSourceStarts: LongArray? = null
    private var restoredSourceEnds: LongArray? = null
    private var restoredTimelineStarts: LongArray? = null

    private val progressTicker = object : Runnable {
        override fun run() {
            if (::playbackEngine.isInitialized && !isDestroyed) updatePlaybackUi()
            uiHandler.postDelayed(this, 33L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedUri = savedInstanceState?.getString(STATE_SELECTED_URI)
        if (savedInstanceState?.containsKey(STATE_CLIP_IDS) == true) {
            restoredClipIds = savedInstanceState.getStringArrayList(STATE_CLIP_IDS)
            restoredSourceStarts = savedInstanceState.getLongArray(STATE_CLIP_SOURCE_STARTS)
            restoredSourceEnds = savedInstanceState.getLongArray(STATE_CLIP_SOURCE_ENDS)
            restoredTimelineStarts = savedInstanceState.getLongArray(STATE_CLIP_TIMELINE_STARTS)
        }

        playbackEngine = AndroidTimelinePlaybackEngine { source ->
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
            text = "Foundation brick 6\nThe clips have acquired wheels."
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(18))
        })

        root.addView(Button(this).apply {
            text = "Open WAV"
            setOnClickListener { chooseWav() }
        })

        statusText = TextView(this).apply {
            text = "Choose a WAV file to inspect, play, edit, and arrange."
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
                selectionTimelineStartFrame = start
                selectionTimelineEndFrameExclusive = end
                updateSelectionUi()
            }
            onClipMoveBoundsRequested = { clipId ->
                val editor = projectEditor
                if (editor == null) {
                    0L..0L
                } else {
                    val bounds = editor.project.clipMoveBounds(TRACK_ID, clipId)
                    bounds.minimumStartFrame..bounds.maximumStartFrame
                }
            }
            onClipMoveCommitted = { clipId, newStart ->
                moveClip(clipId, newStart)
            }
        }
        root.addView(waveformView)

        selectionText = TextView(this).apply {
            text = interactionHint()
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(4))
        }
        root.addView(selectionText)

        val editControlsTop = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        trimButton = Button(this).apply {
            text = "Trim"
            isEnabled = false
            setOnClickListener { trimToSelection() }
        }
        splitButton = Button(this).apply {
            text = "Split"
            isEnabled = false
            setOnClickListener { splitSelection() }
        }
        deleteButton = Button(this).apply {
            text = "Delete"
            isEnabled = false
            setOnClickListener { deleteSelection() }
        }
        editControlsTop.addView(trimButton)
        editControlsTop.addView(splitButton)
        editControlsTop.addView(deleteButton)
        root.addView(editControlsTop)

        val historyControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        undoButton = Button(this).apply {
            text = "Undo"
            isEnabled = false
            setOnClickListener { undoEdit() }
        }
        redoButton = Button(this).apply {
            text = "Redo"
            isEnabled = false
            setOnClickListener { redoEdit() }
        }
        historyControls.addView(undoButton)
        historyControls.addView(redoButton)
        root.addView(historyControls)

        root.addView(TextView(this).apply {
            text = "Drag the C# strip at the top of a clip to move it. Neighbouring clips are hard walls for now, so clips cannot overlap or cross.\nThe original WAV remains untouched."
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
                if (playbackEngine.playheadFrame >= timelineWindowEndFrameExclusive) {
                    playbackEngine.seekTo(timelineWindowStartFrame)
                }
                playbackEngine.play()
            }
        }
        updatePlaybackUi()
    }

    private fun trimToSelection() {
        val editor = projectEditor ?: return
        val start = selectionTimelineStartFrame ?: return
        val end = selectionTimelineEndFrameExclusive ?: return
        val project = editor.project
        val clip = project.tracks.single().clips.singleOrNull() ?: return
        val clipStart = clip.timelineStartFrame
        val clipEnd = project.clipTimelineEndFrameExclusive(clip)
        if (start < clipStart || end > clipEnd || end <= start) return

        val sourceStart = clip.sourceRange.startFrame +
            project.projectFramesToSourceFrames(clip.sourceId, start - clipStart)
        val sourceEnd = clip.sourceRange.startFrame +
            project.projectFramesToSourceFrames(clip.sourceId, end - clipStart)
        if (
            sourceStart <= clip.sourceRange.startFrame &&
            sourceEnd >= clip.sourceRange.endFrameExclusive
        ) return

        playbackEngine.stop()
        try {
            editor.apply(
                TrimClip(
                    trackId = TRACK_ID,
                    clipId = clip.id,
                    newSourceStartFrame = sourceStart.coerceAtLeast(clip.sourceRange.startFrame),
                    newSourceEndFrameExclusive = sourceEnd.coerceAtMost(clip.sourceRange.endFrameExclusive),
                ),
            )
            bindEditorProject(
                "Trimmed nondestructively to ${formatDuration(end - start, loadedSampleRate)}. Source WAV untouched.",
            )
        } catch (error: Throwable) {
            statusText.text = "Trim failed: ${error.message ?: error.javaClass.simpleName}"
        }
    }

    private fun splitSelection() {
        val editor = projectEditor ?: return
        val start = selectionTimelineStartFrame ?: return
        val end = selectionTimelineEndFrameExclusive ?: return
        val before = editor.project

        playbackEngine.stop()
        try {
            editor.apply(
                SplitTimelineRange(
                    trackId = TRACK_ID,
                    startTimelineFrame = start,
                    endTimelineFrameExclusive = end,
                    rightClipIdAtStart = newClipId(),
                    rightClipIdAtEnd = newClipId(),
                ),
            )
            if (editor.project == before) {
                statusText.text = "Selection boundaries were already split."
                return
            }
            val count = editor.project.tracks.single().clips.size
            bindEditorProject(
                statusMessage = "Split selection boundaries. Timeline now has $count clips.",
                preservedSelection = start to end,
            )
        } catch (error: Throwable) {
            statusText.text = "Split failed: ${error.message ?: error.javaClass.simpleName}"
        }
    }

    private fun deleteSelection() {
        val editor = projectEditor ?: return
        val start = selectionTimelineStartFrame ?: return
        val end = selectionTimelineEndFrameExclusive ?: return
        if (!selectionOverlapsAudio(editor.project, start, end)) return

        playbackEngine.stop()
        try {
            editor.apply(
                DeleteTimelineRange(
                    trackId = TRACK_ID,
                    startTimelineFrame = start,
                    endTimelineFrameExclusive = end,
                    rightClipId = newClipId(),
                ),
            )
            bindEditorProject(
                "Deleted selected audio nondestructively. The gap now plays as silence.",
            )
        } catch (error: Throwable) {
            statusText.text = "Delete failed: ${error.message ?: error.javaClass.simpleName}"
        }
    }

    private fun moveClip(clipId: String, requestedStartFrame: Long) {
        val editor = projectEditor ?: return
        val project = editor.project
        val clip = project.tracks.single().clips.firstOrNull { it.id == clipId } ?: return
        val bounds = project.clipMoveBounds(TRACK_ID, clipId)
        val target = bounds.clamp(requestedStartFrame)
        if (target == clip.timelineStartFrame) return

        playbackEngine.stop()
        try {
            editor.apply(MoveClip(TRACK_ID, clipId, target))
            bindEditorProject("Moved clip nondestructively. One drag is one undo step.")
        } catch (error: Throwable) {
            statusText.text = "Move failed: ${error.message ?: error.javaClass.simpleName}"
            bindEditorProject(statusText.text.toString())
        }
    }

    private fun undoEdit() {
        val editor = projectEditor ?: return
        if (!editor.undo()) return
        playbackEngine.stop()
        bindEditorProject("Undo restored the previous timeline state.")
    }

    private fun redoEdit() {
        val editor = projectEditor ?: return
        if (!editor.redo()) return
        playbackEngine.stop()
        bindEditorProject("Redo restored the next timeline state.")
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
        clearRestoredClips()
        loadWav(uri)
    }

    private fun loadWav(uri: Uri) {
        val generation = loadGeneration.incrementAndGet()
        playbackEngine.stop()
        projectEditor = null
        waveformCache = null
        playbackLoaded = false
        loadedChannelCount = 0
        loadedSourceTotalFrames = 0
        timelineWindowStartFrame = 0
        timelineWindowEndFrameExclusive = 0
        selectionTimelineStartFrame = null
        selectionTimelineEndFrameExclusive = null
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
                val baseProject = AudioProject(
                    id = "brick6-project",
                    title = displayName,
                    timelineRate = source.sampleRate,
                    sources = mapOf(source.id to source),
                    tracks = listOf(
                        ProjectTrack(
                            id = TRACK_ID,
                            name = displayName,
                            clips = listOf(
                                AudioClip(
                                    id = INITIAL_CLIP_ID,
                                    sourceId = source.id,
                                    sourceRange = SourceRange(0, source.totalFrames),
                                    timelineStartFrame = 0,
                                ),
                            ),
                        ),
                    ),
                )
                val restoredProject = restoreProjectIfPossible(baseProject, source, displayName)

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
                    loadedChannelCount = metadata.info.channelCount
                    loadedSourceTotalFrames = metadata.info.totalFrames
                    waveformCache = cache
                    metadataText.text = details
                    projectEditor = ProjectEditor(restoredProject)
                    clearRestoredClips()
                    bindEditorProject("Waveform ready. Clip movement armed.")
                }
            } catch (error: Throwable) {
                runOnUiThread {
                    if (generation != loadGeneration.get() || isDestroyed) return@runOnUiThread
                    projectEditor = null
                    waveformCache = null
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

    private fun restoreProjectIfPossible(
        baseProject: AudioProject,
        source: AudioSource,
        displayName: String,
    ): AudioProject {
        val ids = restoredClipIds ?: return baseProject
        val sourceStarts = restoredSourceStarts ?: return baseProject
        val sourceEnds = restoredSourceEnds ?: return baseProject
        val timelineStarts = restoredTimelineStarts ?: return baseProject
        if (
            ids.size != sourceStarts.size ||
            ids.size != sourceEnds.size ||
            ids.size != timelineStarts.size
        ) return baseProject

        return runCatching {
            val clips = ids.indices.map { index ->
                require(sourceStarts[index] >= 0)
                require(sourceEnds[index] <= source.totalFrames)
                require(sourceEnds[index] > sourceStarts[index])
                require(timelineStarts[index] >= 0)
                AudioClip(
                    id = ids[index],
                    sourceId = source.id,
                    sourceRange = SourceRange(sourceStarts[index], sourceEnds[index]),
                    timelineStartFrame = timelineStarts[index],
                )
            }
            baseProject.copy(
                tracks = listOf(ProjectTrack(TRACK_ID, displayName, clips)),
            ).validate()
        }.getOrElse { baseProject }
    }

    private fun bindEditorProject(
        statusMessage: String,
        preservedSelection: Pair<Long, Long>? = null,
    ) {
        val editor = projectEditor ?: return
        val project = editor.project
        val track = project.tracks.single()
        val clips = track.clips.sortedBy { it.timelineStartFrame }
        val cache = waveformCache ?: return

        selectionTimelineStartFrame = null
        selectionTimelineEndFrameExclusive = null
        lastShownPlaybackError = null

        if (clips.isNotEmpty()) {
            timelineWindowStartFrame = clips.minOf { it.timelineStartFrame }
            timelineWindowEndFrameExclusive = clips.maxOf { project.clipTimelineEndFrameExclusive(it) }
            playbackEngine.load(project)
            playbackLoaded = true
        } else {
            playbackEngine.stop()
            playbackLoaded = false
            timelineWindowStartFrame = 0
            timelineWindowEndFrameExclusive = loadedSourceTotalFrames.coerceAtLeast(1)
        }

        waveformView.setTimeline(
            cache = cache,
            channelCount = loadedChannelCount,
            clips = timelineWaveformClips(project),
            visibleTimelineStartFrame = timelineWindowStartFrame,
            visibleTimelineEndFrameExclusive = timelineWindowEndFrameExclusive,
        )

        preservedSelection?.let { (start, end) ->
            val clampedStart = start.coerceIn(timelineWindowStartFrame, timelineWindowEndFrameExclusive)
            val clampedEnd = end.coerceIn(timelineWindowStartFrame, timelineWindowEndFrameExclusive)
            if (clampedEnd > clampedStart) {
                selectionTimelineStartFrame = clampedStart
                selectionTimelineEndFrameExclusive = clampedEnd
                waveformView.setSelection(clampedStart, clampedEnd, notify = false)
            }
        }

        statusText.text = statusMessage
        updateSelectionUi()
        updatePlaybackUi()
    }

    private fun timelineWaveformClips(project: AudioProject): List<TimelineWaveformClip> =
        project.tracks.single().clips.sortedBy { it.timelineStartFrame }.map { clip ->
            TimelineWaveformClip(
                id = clip.id,
                sourceId = clip.sourceId,
                sourceStartFrame = clip.sourceRange.startFrame,
                sourceEndFrameExclusive = clip.sourceRange.endFrameExclusive,
                timelineStartFrame = clip.timelineStartFrame,
                timelineEndFrameExclusive = project.clipTimelineEndFrameExclusive(clip),
            )
        }

    private fun updateSelectionUi() {
        if (!::selectionText.isInitialized || !::trimButton.isInitialized) return
        val editor = projectEditor
        val start = selectionTimelineStartFrame
        val end = selectionTimelineEndFrameExclusive

        if (editor == null || start == null || end == null || end <= start) {
            selectionText.text = interactionHint()
            trimButton.isEnabled = false
            splitButton.isEnabled = false
            deleteButton.isEnabled = false
            updateHistoryButtons()
            return
        }

        val relativeStart = start - timelineWindowStartFrame
        val relativeEnd = end - timelineWindowStartFrame
        selectionText.text = buildString {
            append("Selection ")
            append(formatDuration(relativeStart, loadedSampleRate))
            append(" → ")
            append(formatDuration(relativeEnd, loadedSampleRate))
            append("  (")
            append(formatDuration(end - start, loadedSampleRate))
            append(')')
        }

        val project = editor.project
        val clips = project.tracks.single().clips
        val single = clips.singleOrNull()
        trimButton.isEnabled = single != null && selectionInsideClip(project, single, start, end) &&
            (start != single.timelineStartFrame || end != project.clipTimelineEndFrameExclusive(single))
        splitButton.isEnabled = clips.isNotEmpty() && selectionCanCreateSplit(project, start, end)
        deleteButton.isEnabled = selectionOverlapsAudio(project, start, end)
        updateHistoryButtons()
    }

    private fun updateHistoryButtons() {
        if (!::undoButton.isInitialized || !::redoButton.isInitialized) return
        val editor = projectEditor
        undoButton.isEnabled = editor?.canUndo == true
        redoButton.isEnabled = editor?.canRedo == true
    }

    private fun updatePlaybackUi() {
        if (!::playPauseButton.isInitialized) return

        playPauseButton.isEnabled = playbackLoaded
        playPauseButton.text = if (playbackEngine.state == PlaybackState.PLAYING) "Pause" else "Play"
        stopButton.isEnabled = playbackLoaded &&
            (playbackEngine.state != PlaybackState.STOPPED || playbackEngine.playheadFrame > timelineWindowStartFrame)

        if (timelineWindowEndFrameExclusive <= timelineWindowStartFrame) {
            timeText.text = "0:00.000 / 0:00.000"
            return
        }

        val durationFrames = timelineWindowEndFrameExclusive - timelineWindowStartFrame
        if (!playbackLoaded) {
            timeText.text = "0:00.000 / ${formatDuration(durationFrames, loadedSampleRate)}"
            waveformView.setPlayheadFrame(timelineWindowStartFrame)
            return
        }

        val frame = playbackEngine.playheadFrame
            .coerceIn(timelineWindowStartFrame, timelineWindowEndFrameExclusive)
        waveformView.setPlayheadFrame(frame)
        val relativeFrame = frame - timelineWindowStartFrame
        timeText.text = "${formatDuration(relativeFrame, loadedSampleRate)} / ${formatDuration(durationFrames, loadedSampleRate)}"

        val error = playbackEngine.lastError
        if (error != null && error != lastShownPlaybackError) {
            lastShownPlaybackError = error
            statusText.text = "Playback error: $error"
        }
    }

    private fun selectionInsideClip(
        project: AudioProject,
        clip: AudioClip,
        start: Long,
        end: Long,
    ): Boolean =
        start >= clip.timelineStartFrame && end <= project.clipTimelineEndFrameExclusive(clip)

    private fun selectionCanCreateSplit(project: AudioProject, start: Long, end: Long): Boolean {
        val clips = project.tracks.single().clips
        return clips.any { clip ->
            val clipStart = clip.timelineStartFrame
            val clipEnd = project.clipTimelineEndFrameExclusive(clip)
            (start > clipStart && start < clipEnd) || (end > clipStart && end < clipEnd)
        }
    }

    private fun selectionOverlapsAudio(project: AudioProject, start: Long, end: Long): Boolean =
        project.tracks.single().clips.any { clip ->
            val clipStart = clip.timelineStartFrame
            val clipEnd = project.clipTimelineEndFrameExclusive(clip)
            maxOf(start, clipStart) < minOf(end, clipEnd)
        }

    private fun interactionHint(): String =
        "Drag the waveform body to select. Drag a C# header to move that clip. Tap to seek."

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

    private fun newClipId(): String = "clip-${UUID.randomUUID()}"

    private fun clearRestoredClips() {
        restoredClipIds = null
        restoredSourceStarts = null
        restoredSourceEnds = null
        restoredTimelineStarts = null
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
        projectEditor?.project?.tracks?.singleOrNull()?.clips?.let { clips ->
            outState.putStringArrayList(STATE_CLIP_IDS, ArrayList(clips.map { it.id }))
            outState.putLongArray(STATE_CLIP_SOURCE_STARTS, clips.map { it.sourceRange.startFrame }.toLongArray())
            outState.putLongArray(STATE_CLIP_SOURCE_ENDS, clips.map { it.sourceRange.endFrameExclusive }.toLongArray())
            outState.putLongArray(STATE_CLIP_TIMELINE_STARTS, clips.map { it.timelineStartFrame }.toLongArray())
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
        private const val STATE_CLIP_IDS = "clip_ids"
        private const val STATE_CLIP_SOURCE_STARTS = "clip_source_starts"
        private const val STATE_CLIP_SOURCE_ENDS = "clip_source_ends"
        private const val STATE_CLIP_TIMELINE_STARTS = "clip_timeline_starts"
        private const val TRACK_ID = "track-1"
        private const val INITIAL_CLIP_ID = "clip-1"
    }
}
