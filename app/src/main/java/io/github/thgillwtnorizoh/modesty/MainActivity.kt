package io.github.thgillwtnorizoh.modesty

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.thgillwtnorizoh.modesty.core.dsp.decibelsToLinearGain
import io.github.thgillwtnorizoh.modesty.core.dsp.linearGainToDecibels
import io.github.thgillwtnorizoh.modesty.core.editing.AmplifyTimelineRange
import io.github.thgillwtnorizoh.modesty.core.editing.DeleteTimelineRange
import io.github.thgillwtnorizoh.modesty.core.editing.MoveClip
import io.github.thgillwtnorizoh.modesty.core.editing.ProjectEditor
import io.github.thgillwtnorizoh.modesty.core.editing.SplitTimelineRange
import io.github.thgillwtnorizoh.modesty.core.editing.TrimClip
import io.github.thgillwtnorizoh.modesty.core.editing.appendWholeSourceAtEndOperation
import io.github.thgillwtnorizoh.modesty.core.editing.clipContainingTimelineRange
import io.github.thgillwtnorizoh.modesty.core.editing.clipMoveBounds
import io.github.thgillwtnorizoh.modesty.core.io.Pcm16WavEncoder
import io.github.thgillwtnorizoh.modesty.core.io.WavDecoder
import io.github.thgillwtnorizoh.modesty.core.io.WavEncoding
import io.github.thgillwtnorizoh.modesty.core.model.AudioClip
import io.github.thgillwtnorizoh.modesty.core.model.AudioProject
import io.github.thgillwtnorizoh.modesty.core.model.AudioSource
import io.github.thgillwtnorizoh.modesty.core.model.AudioTrack as ProjectTrack
import io.github.thgillwtnorizoh.modesty.core.model.SourceRange
import io.github.thgillwtnorizoh.modesty.core.playback.PlaybackState
import io.github.thgillwtnorizoh.modesty.core.render.TimelineRenderer
import io.github.thgillwtnorizoh.modesty.core.waveform.InMemoryWaveformCache
import io.github.thgillwtnorizoh.modesty.core.waveform.WaveformPyramid
import io.github.thgillwtnorizoh.modesty.core.waveform.WaveformPyramidBuilder
import io.github.thgillwtnorizoh.modesty.platform.playback.AndroidTimelinePlaybackEngine
import io.github.thgillwtnorizoh.modesty.ui.TimelineWaveformClip
import io.github.thgillwtnorizoh.modesty.ui.WaveformView
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private lateinit var statusText: TextView
    private lateinit var metadataText: TextView
    private lateinit var waveformView: WaveformView
    private lateinit var playPauseButton: Button
    private lateinit var stopButton: Button
    private lateinit var quickJoinButton: Button
    private lateinit var addWavButton: Button
    private lateinit var trimButton: Button
    private lateinit var splitButton: Button
    private lateinit var deleteButton: Button
    private lateinit var amplifyButton: Button
    private lateinit var exportButton: Button
    private lateinit var undoButton: Button
    private lateinit var redoButton: Button
    private lateinit var timeText: TextView
    private lateinit var selectionText: TextView
    private lateinit var gainSummaryText: TextView
    private lateinit var playbackEngine: AndroidTimelinePlaybackEngine

    private val worker = Executors.newSingleThreadExecutor()
    private val loadGeneration = AtomicInteger(0)
    private val uiHandler = Handler(Looper.getMainLooper())

    private var projectEditor: ProjectEditor? = null
    private var waveformCache: InMemoryWaveformCache? = null
    private val sourceFormats = linkedMapOf<String, SourceFormatInfo>()
    private var playbackLoaded = false
    private var exportInProgress = false
    private var importInProgress = false
    private var quickJoinStage = QuickJoinStage.IDLE
    private var loadedSampleRate = 48_000
    private var loadedChannelCount = 0
    private var loadedSourceTotalFrames = 0L
    private var timelineWindowStartFrame = 0L
    private var timelineWindowEndFrameExclusive = 0L
    private var selectionTimelineStartFrame: Long? = null
    private var selectionTimelineEndFrameExclusive: Long? = null
    private var lastShownPlaybackError: String? = null

    private var restoredSourceIds: ArrayList<String>? = null
    private var restoredSourceLocations: ArrayList<String>? = null
    private var restoredClipIds: ArrayList<String>? = null
    private var restoredClipSourceIds: ArrayList<String>? = null
    private var restoredSourceStarts: LongArray? = null
    private var restoredSourceEnds: LongArray? = null
    private var restoredTimelineStarts: LongArray? = null
    private var restoredGains: FloatArray? = null

    private val progressTicker = object : Runnable {
        override fun run() {
            if (::playbackEngine.isInitialized && !isDestroyed) updatePlaybackUi()
            uiHandler.postDelayed(this, 33L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        restoreBundleFields(savedInstanceState)
        val retained = lastNonConfigurationInstance as? RetainedSession

        playbackEngine = AndroidTimelinePlaybackEngine { source -> decoderFor(source) }
        setContentView(buildContent())
        uiHandler.post(progressTicker)

        if (retained != null) {
            projectEditor = retained.editor
            waveformCache = retained.waveformCache
            sourceFormats.clear()
            sourceFormats.putAll(retained.sourceFormats)
            quickJoinStage = retained.quickJoinStage
            val project = retained.editor.project
            loadedSampleRate = project.timelineRate.hz
            loadedChannelCount = project.sources.values.firstOrNull()?.channelCount ?: 0
            loadedSourceTotalFrames = retained.loadedSourceTotalFrames
            clearRestoredState()
            bindEditorProject(
                statusMessage = if (quickJoinStage == QuickJoinStage.IDLE) {
                    "Restored live editor session after rotation. Undo/Redo history preserved."
                } else {
                    "Restored live Quick Join session after rotation."
                },
                preservedSelection = retained.selectionStart?.let { start ->
                    retained.selectionEnd?.let { end -> start to end }
                },
            )
        } else if (!restoredSourceIds.isNullOrEmpty() && !restoredSourceLocations.isNullOrEmpty()) {
            loadRestoredProject()
        } else {
            updateFileActionButtons()
        }
    }

    private fun restoreBundleFields(state: Bundle?) {
        if (state == null) return
        restoredSourceIds = state.getStringArrayList(STATE_SOURCE_IDS)
        restoredSourceLocations = state.getStringArrayList(STATE_SOURCE_LOCATIONS)
        restoredClipIds = state.getStringArrayList(STATE_CLIP_IDS)
        restoredClipSourceIds = state.getStringArrayList(STATE_CLIP_SOURCE_IDS)
        restoredSourceStarts = state.getLongArray(STATE_CLIP_SOURCE_STARTS)
        restoredSourceEnds = state.getLongArray(STATE_CLIP_SOURCE_ENDS)
        restoredTimelineStarts = state.getLongArray(STATE_CLIP_TIMELINE_STARTS)
        restoredGains = state.getFloatArray(STATE_CLIP_GAINS)
        quickJoinStage = state.getString(STATE_QUICK_JOIN_STAGE)
            ?.let { name -> runCatching { QuickJoinStage.valueOf(name) }.getOrNull() }
            ?: QuickJoinStage.IDLE
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
            text = "Foundation brick 10\nQuick Join has entered the building."
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(12))
        })

        quickJoinButton = Button(this).apply {
            text = "Quick Join"
            setOnClickListener { beginQuickJoin() }
        }
        root.addView(quickJoinButton)

        val fileControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        fileControls.addView(Button(this).apply {
            text = "Open WAV"
            setOnClickListener {
                quickJoinStage = QuickJoinStage.IDLE
                updateFileActionButtons()
                chooseWav(REQUEST_OPEN_WAV)
            }
        })
        addWavButton = Button(this).apply {
            text = "Add WAV"
            isEnabled = false
            setOnClickListener { chooseWav(REQUEST_ADD_WAV) }
        }
        fileControls.addView(addWavButton)
        root.addView(fileControls)

        statusText = TextView(this).apply {
            text = "Quick Join picks A then B. Open WAV and Add WAV remain the normal editor doors."
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
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(320))
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
                projectEditor?.project?.clipMoveBounds(TRACK_ID, clipId)?.let { bounds ->
                    bounds.minimumStartFrame..bounds.maximumStartFrame
                } ?: (0L..0L)
            }
            onClipMoveCommitted = { clipId, newStart -> moveClip(clipId, newStart) }
        }
        root.addView(waveformView)

        selectionText = TextView(this).apply {
            text = interactionHint()
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
        editControls.addView(trimButton)
        editControls.addView(splitButton)
        editControls.addView(deleteButton)
        root.addView(editControls)

        amplifyButton = Button(this).apply {
            text = "Amplify…"
            isEnabled = false
            setOnClickListener { showAmplifyDialog() }
        }
        root.addView(amplifyButton)

        gainSummaryText = TextView(this).apply {
            text = "Clip gain: waiting for audio."
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(6))
        }
        root.addView(gainSummaryText)

        exportButton = Button(this).apply {
            text = "Export WAV"
            isEnabled = false
            setOnClickListener { chooseExportWav() }
        }
        root.addView(exportButton)

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
            text = "Brick #10 Quick Join is only a two-picker front door into the existing project editor. B is appended with the same operation used by Add WAV, then preview/edit/export use the normal playback and renderer paths."
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

    private fun showAmplifyDialog() {
        val editor = projectEditor ?: return
        val start = selectionTimelineStartFrame ?: return
        val end = selectionTimelineEndFrameExclusive ?: return
        if (!selectionOverlapsAudio(editor.project, start, end)) return

        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or
                InputType.TYPE_NUMBER_FLAG_DECIMAL or
                InputType.TYPE_NUMBER_FLAG_SIGNED
            setText("6.0")
            selectAll()
        }

        AlertDialog.Builder(this)
            .setTitle("Amplify selection")
            .setMessage(
                "Enter a finite gain in dB. There is no arbitrary per-apply cap. " +
                    "Very large positive gain will likely hard-clip.",
            )
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Apply") { _, _ -> applyGainFromText(input.text.toString(), preview = false) }
            .setPositiveButton("Apply & Preview") { _, _ -> applyGainFromText(input.text.toString(), preview = true) }
            .show()
    }

    private fun applyGainFromText(text: String, preview: Boolean) {
        val decibels = text.trim().toFloatOrNull()
        if (decibels == null || !decibels.isFinite()) {
            statusText.text = "Amplify needs a finite dB number."
            return
        }
        if (abs(decibels) < 0.0001f) {
            statusText.text = "0 dB changes nothing, which is impressively accurate but not very exciting."
            return
        }

        val gainMultiplier = runCatching { decibelsToLinearGain(decibels) }
            .getOrElse {
                statusText.text = "That dB value is beyond the finite gain range the engine can represent."
                return
            }
        amplifySelection(decibels, gainMultiplier, preview)
    }

    private fun amplifySelection(decibels: Float, gainMultiplier: Float, preview: Boolean) {
        val editor = projectEditor ?: return
        val start = selectionTimelineStartFrame ?: return
        val end = selectionTimelineEndFrameExclusive ?: return
        if (!selectionOverlapsAudio(editor.project, start, end)) return

        playbackEngine.stop()
        try {
            editor.apply(
                AmplifyTimelineRange(
                    trackId = TRACK_ID,
                    startTimelineFrame = start,
                    endTimelineFrameExclusive = end,
                    gainMultiplier = gainMultiplier,
                    rightClipIdAtStart = newClipId(),
                    rightClipIdAtEnd = newClipId(),
                ),
            )
            val warning = when {
                decibels > HIGH_GAIN_WARNING_DB ->
                    " Extreme positive gain will likely hard-clip; preview at a comfortable device volume."
                decibels > 0f -> " Positive gain may clip."
                else -> ""
            }
            bindEditorProject(
                statusMessage = "Applied ${formatDb(decibels)} nondestructively.$warning",
                preservedSelection = start to end,
            )
            if (preview && playbackLoaded) {
                playbackEngine.play(startFrame = start, endFrameExclusive = end)
                updatePlaybackUi()
            }
        } catch (error: Throwable) {
            statusText.text = "Amplify failed: ${error.message ?: error.javaClass.simpleName}"
        }
    }

    private fun trimToSelection() {
        val editor = projectEditor ?: return
        val start = selectionTimelineStartFrame ?: return
        val end = selectionTimelineEndFrameExclusive ?: return
        val project = editor.project
        val clip = project.clipContainingTimelineRange(TRACK_ID, start, end) ?: return
        val clipStart = clip.timelineStartFrame

        val sourceStart = clip.sourceRange.startFrame +
            project.projectFramesToSourceFrames(clip.sourceId, start - clipStart)
        val sourceEnd = clip.sourceRange.startFrame +
            project.projectFramesToSourceFrames(clip.sourceId, end - clipStart)
        if (sourceStart <= clip.sourceRange.startFrame && sourceEnd >= clip.sourceRange.endFrameExclusive) return

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
            bindEditorProject("Trimmed that clip nondestructively to ${formatDuration(end - start, loadedSampleRate)}.")
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
            bindEditorProject(
                "Split selection boundaries. Timeline now has ${editor.project.tracks.single().clips.size} clips.",
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
            bindEditorProject("Deleted selected audio nondestructively. The gap now plays as silence.")
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
            bindEditorProject("Move failed: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun undoEdit() {
        val editor = projectEditor ?: return
        if (!editor.undo()) return
        playbackEngine.stop()
        bindEditorProject("Undo restored the previous project state.")
    }

    private fun redoEdit() {
        val editor = projectEditor ?: return
        if (!editor.redo()) return
        playbackEngine.stop()
        bindEditorProject("Redo restored the next project state.")
    }

    private fun beginQuickJoin() {
        if (importInProgress || exportInProgress || quickJoinStage != QuickJoinStage.IDLE) return
        quickJoinStage = QuickJoinStage.PICKING_FIRST
        statusText.text = "Quick Join: choose WAV A."
        updateFileActionButtons()
        chooseWav(REQUEST_QUICK_JOIN_FIRST)
    }

    private fun handlePickerCanceled(requestCode: Int) {
        when (requestCode) {
            REQUEST_QUICK_JOIN_FIRST -> {
                quickJoinStage = QuickJoinStage.IDLE
                statusText.text = "Quick Join canceled before A. Your existing project was not changed."
                updateFileActionButtons()
            }

            REQUEST_QUICK_JOIN_SECOND -> {
                quickJoinStage = QuickJoinStage.IDLE
                statusText.text = "Quick Join stopped after A. A remains open as a normal editable project."
                updateFileActionButtons()
            }
        }
    }

    private fun chooseWav(requestCode: Int) {
        if (requestCode == REQUEST_ADD_WAV && (projectEditor == null || importInProgress || exportInProgress)) return
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf("audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave", "application/octet-stream"),
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, requestCode)
    }

    private fun chooseExportWav() {
        val project = projectEditor?.project ?: return
        if (
            project.tracks.singleOrNull()?.clips.isNullOrEmpty() ||
            exportInProgress ||
            importInProgress ||
            quickJoinStage != QuickJoinStage.IDLE
        ) return
        val stem = project.title.substringBeforeLast('.', project.title).ifBlank { "modesty-export" }
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/wav"
            putExtra(Intent.EXTRA_TITLE, "${stem}_modesty.wav")
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_EXPORT_WAV)
    }

    @Deprecated("Legacy Activity callback keeps the foundation dependency-free")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) {
            handlePickerCanceled(requestCode)
            return
        }
        val uri = data.data ?: return

        when (requestCode) {
            REQUEST_OPEN_WAV -> {
                quickJoinStage = QuickJoinStage.IDLE
                persistReadPermission(uri)
                clearRestoredState()
                loadNewProject(uri)
            }

            REQUEST_ADD_WAV -> {
                persistReadPermission(uri)
                addWavToProject(uri)
            }

            REQUEST_QUICK_JOIN_FIRST -> {
                persistReadPermission(uri)
                clearRestoredState()
                quickJoinStage = QuickJoinStage.LOADING_FIRST
                updateFileActionButtons()
                loadNewProject(uri, quickJoinFirst = true)
            }

            REQUEST_QUICK_JOIN_SECOND -> {
                persistReadPermission(uri)
                quickJoinStage = QuickJoinStage.LOADING_SECOND
                updateFileActionButtons()
                addWavToProject(uri, quickJoinSecond = true)
            }

            REQUEST_EXPORT_WAV -> exportProjectTo(uri)
        }
    }

    private fun persistReadPermission(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Some providers grant only temporary access. The current session remains usable.
        }
    }

    private fun exportProjectTo(uri: Uri) {
        val project = projectEditor?.project ?: return
        if (project.tracks.singleOrNull()?.clips.isNullOrEmpty() || exportInProgress || importInProgress) return

        playbackEngine.stop()
        exportInProgress = true
        updateFileActionButtons()
        statusText.text = "Packing WAV bento… 0%"

        worker.execute {
            try {
                val renderer = TimelineRenderer(project) { source -> decoderFor(source) }
                val info = renderer.outputInfo
                var lastShownPercent = -5

                val rawOutput = contentResolver.openOutputStream(uri, "w")
                    ?: error("Android could not open the destination document")
                rawOutput.use { stream ->
                    Pcm16WavEncoder(
                        rawOutput = stream,
                        sampleRate = info.sampleRate,
                        channelCount = info.channelCount,
                        totalFrames = info.totalFrames,
                    ).use { encoder ->
                        renderer.render(encoder) { progress ->
                            val percent = (progress * 100f).roundToInt().coerceIn(0, 100)
                            if (percent >= lastShownPercent + 5 || percent == 100) {
                                lastShownPercent = percent
                                runOnUiThread {
                                    if (!isDestroyed && exportInProgress) {
                                        statusText.text = "Packing WAV bento… $percent%"
                                    }
                                }
                            }
                        }
                    }
                }

                runOnUiThread {
                    if (!isDestroyed) {
                        statusText.text = "Exported ${formatDuration(info.totalFrames, info.sampleRate.hz)} as 16-bit PCM WAV. Bento packed."
                    }
                }
            } catch (error: Throwable) {
                runCatching { contentResolver.delete(uri, null, null) }
                runOnUiThread {
                    if (!isDestroyed) statusText.text = "Export failed: ${error.message ?: error.javaClass.simpleName}"
                }
            } finally {
                runOnUiThread {
                    if (!isDestroyed) {
                        exportInProgress = false
                        updateFileActionButtons()
                    }
                }
            }
        }
    }

    private fun loadNewProject(uri: Uri, quickJoinFirst: Boolean = false) {
        val generation = loadGeneration.incrementAndGet()
        resetForLoad(
            if (quickJoinFirst) "Quick Join: reading WAV A and building its waveform…"
            else "Reading first WAV and building waveform…",
        )

        worker.execute {
            try {
                val loaded = decodeSource(uri, newSourceId())
                val displayName = loaded.format.displayName
                val source = loaded.source
                val cache = InMemoryWaveformCache().put(source.id, loaded.pyramid)
                val project = AudioProject(
                    id = "brick10-project",
                    title = displayName,
                    timelineRate = source.sampleRate,
                    sources = linkedMapOf(source.id to source),
                    tracks = listOf(
                        ProjectTrack(
                            id = TRACK_ID,
                            name = displayName,
                            clips = listOf(
                                AudioClip(
                                    id = newClipId(),
                                    sourceId = source.id,
                                    sourceRange = SourceRange(0, source.totalFrames),
                                    timelineStartFrame = 0,
                                ),
                            ),
                        ),
                    ),
                )

                runOnUiThread {
                    if (generation != loadGeneration.get() || isDestroyed) return@runOnUiThread
                    sourceFormats.clear()
                    sourceFormats[source.id] = loaded.format
                    installLoadedProject(project, cache)
                    clearRestoredState()
                    if (quickJoinFirst) {
                        quickJoinStage = QuickJoinStage.PICKING_SECOND
                        bindEditorProject("Quick Join: $displayName is A. Now choose WAV B.")
                        chooseWav(REQUEST_QUICK_JOIN_SECOND)
                    } else {
                        bindEditorProject("Waveform ready. Add WAV can bring in another source.")
                    }
                }
            } catch (error: Throwable) {
                showLoadFailure(generation, error, resetQuickJoin = quickJoinFirst)
            }
        }
    }

    private fun addWavToProject(uri: Uri, quickJoinSecond: Boolean = false) {
        val initialProject = projectEditor?.project ?: return
        if (importInProgress || exportInProgress) return
        val generation = loadGeneration.get()
        val expectedRate = initialProject.timelineRate
        val expectedChannels = initialProject.sources.values.firstOrNull()?.channelCount ?: return

        importInProgress = true
        updateFileActionButtons()
        statusText.text = if (quickJoinSecond) {
            "Quick Join: reading WAV B and building its waveform…"
        } else {
            "Reading another WAV and building its waveform…"
        }

        worker.execute {
            try {
                val sourceId = newSourceId()
                val loaded = decodeSource(uri, sourceId)
                require(loaded.source.sampleRate == expectedRate) {
                    "That WAV is ${loaded.source.sampleRate.hz} Hz; this project is ${expectedRate.hz} Hz. Resampling comes later."
                }
                require(loaded.source.channelCount == expectedChannels) {
                    "That WAV has ${loaded.source.channelCount} channels; this project has $expectedChannels. Channel conversion comes later."
                }
                val displayName = loaded.format.displayName

                runOnUiThread {
                    if (generation != loadGeneration.get() || isDestroyed) return@runOnUiThread
                    val editor = projectEditor ?: return@runOnUiThread
                    val current = editor.project
                    if (
                        current.timelineRate != loaded.source.sampleRate ||
                        current.sources.values.firstOrNull()?.channelCount != loaded.source.channelCount
                    ) {
                        if (quickJoinSecond) quickJoinStage = QuickJoinStage.IDLE
                        statusText.text = if (quickJoinSecond) {
                            "Quick Join stopped because the project format changed. A remains editable."
                        } else {
                            "Project format changed while importing. Please add the WAV again."
                        }
                        updateFileActionButtons()
                        return@runOnUiThread
                    }

                    waveformCache?.put(loaded.source.id, loaded.pyramid)
                        ?: error("Waveform cache disappeared during import")
                    sourceFormats[loaded.source.id] = loaded.format
                    editor.apply(
                        current.appendWholeSourceAtEndOperation(
                            trackId = TRACK_ID,
                            source = loaded.source,
                            clipId = newClipId(),
                        ),
                    )
                    loadedSourceTotalFrames = maxOf(loadedSourceTotalFrames, loaded.source.totalFrames)
                    if (quickJoinSecond) {
                        quickJoinStage = QuickJoinStage.IDLE
                        bindEditorProject(
                            "Quick Join ready: A + $displayName. Preview it, edit if needed, then Export WAV.",
                        )
                    } else {
                        bindEditorProject(
                            "Added $displayName as source ${editor.project.sources.size}. It starts exactly after the previous last clip.",
                        )
                    }
                }
            } catch (error: Throwable) {
                runOnUiThread {
                    if (generation == loadGeneration.get() && !isDestroyed) {
                        if (quickJoinSecond) {
                            quickJoinStage = QuickJoinStage.IDLE
                            statusText.text =
                                "Quick Join could not add B: ${error.message ?: error.javaClass.simpleName}. A remains editable."
                        } else {
                            statusText.text = "Could not add WAV: ${error.message ?: error.javaClass.simpleName}"
                        }
                    }
                }
            } finally {
                runOnUiThread {
                    if (!isDestroyed) {
                        importInProgress = false
                        updateFileActionButtons()
                    }
                }
            }
        }
    }

    private fun loadRestoredProject() {
        val sourceIds = restoredSourceIds ?: return
        val sourceLocations = restoredSourceLocations ?: return
        if (sourceIds.isEmpty() || sourceIds.size != sourceLocations.size) {
            statusText.text = "Could not restore project sources."
            return
        }

        val generation = loadGeneration.incrementAndGet()
        resetForLoad("Restoring project sources and waveform caches…")

        worker.execute {
            try {
                val cache = InMemoryWaveformCache()
                val sources = linkedMapOf<String, AudioSource>()
                val formats = linkedMapOf<String, SourceFormatInfo>()
                var expectedRate: Int? = null
                var expectedChannels: Int? = null

                sourceIds.indices.forEach { index ->
                    val uri = Uri.parse(sourceLocations[index])
                    val loaded = decodeSource(uri, sourceIds[index])
                    val source = loaded.source
                    if (expectedRate == null) {
                        expectedRate = source.sampleRate.hz
                        expectedChannels = source.channelCount
                    } else {
                        require(source.sampleRate.hz == expectedRate && source.channelCount == expectedChannels) {
                            "Saved project source formats no longer match"
                        }
                    }
                    sources[source.id] = source
                    formats[source.id] = loaded.format
                    cache.put(source.id, loaded.pyramid)
                }

                val firstSource = sources.values.first()
                val title = formats[firstSource.id]?.displayName ?: "Restored WAV"
                val clips = restoreClips(sources)
                val project = AudioProject(
                    id = "brick10-project",
                    title = title,
                    timelineRate = firstSource.sampleRate,
                    sources = sources,
                    tracks = listOf(ProjectTrack(TRACK_ID, title, clips)),
                ).validate()

                runOnUiThread {
                    if (generation != loadGeneration.get() || isDestroyed) return@runOnUiThread
                    sourceFormats.clear()
                    sourceFormats.putAll(formats)
                    installLoadedProject(project, cache)
                    clearRestoredState()
                    bindEditorProject(
                        "Restored ${project.sources.size} source${if (project.sources.size == 1) "" else "s"} and ${clips.size} clip${if (clips.size == 1) "" else "s"}. Process-death restore does not include edit history.",
                    )
                }
            } catch (error: Throwable) {
                showLoadFailure(generation, error, resetQuickJoin = true)
            }
        }
    }

    private fun restoreClips(sources: Map<String, AudioSource>): List<AudioClip> {
        val ids = restoredClipIds ?: arrayListOf()
        val sourceIds = restoredClipSourceIds ?: arrayListOf()
        val sourceStarts = restoredSourceStarts ?: LongArray(0)
        val sourceEnds = restoredSourceEnds ?: LongArray(0)
        val timelineStarts = restoredTimelineStarts ?: LongArray(0)
        val gains = restoredGains ?: FloatArray(ids.size) { 1f }
        require(
            ids.size == sourceIds.size &&
                ids.size == sourceStarts.size &&
                ids.size == sourceEnds.size &&
                ids.size == timelineStarts.size &&
                ids.size == gains.size,
        ) { "Saved clip metadata is incomplete" }

        return ids.indices.map { index ->
            val source = sources[sourceIds[index]] ?: error("Saved clip references a missing source")
            require(sourceStarts[index] >= 0 && sourceEnds[index] <= source.totalFrames)
            require(sourceEnds[index] > sourceStarts[index])
            require(timelineStarts[index] >= 0)
            require(gains[index] >= 0f && gains[index].isFinite())
            AudioClip(
                id = ids[index],
                sourceId = source.id,
                sourceRange = SourceRange(sourceStarts[index], sourceEnds[index]),
                timelineStartFrame = timelineStarts[index],
                gain = gains[index],
            )
        }.sortedBy { it.timelineStartFrame }
    }

    private enum class QuickJoinStage {
        IDLE,
        PICKING_FIRST,
        LOADING_FIRST,
        PICKING_SECOND,
        LOADING_SECOND,
    }

    private data class SourceFormatInfo(
        val displayName: String,
        val bitsPerSample: Int,
        val encodingLabel: String,
    )

    private data class LoadedSource(
        val source: AudioSource,
        val pyramid: WaveformPyramid,
        val format: SourceFormatInfo,
    )

    private data class RetainedSession(
        val editor: ProjectEditor,
        val waveformCache: InMemoryWaveformCache,
        val sourceFormats: Map<String, SourceFormatInfo>,
        val loadedSourceTotalFrames: Long,
        val selectionStart: Long?,
        val selectionEnd: Long?,
        val quickJoinStage: QuickJoinStage,
    )

    private fun decodeSource(uri: Uri, sourceId: String): LoadedSource {
        val decoder = WavDecoder {
            contentResolver.openInputStream(uri) ?: error("Android could not open this document")
        }
        val metadata = decoder.metadata
        val pyramid = try {
            WaveformPyramidBuilder.build(decoder)
        } finally {
            decoder.close()
        }
        val format = SourceFormatInfo(
            displayName = queryDisplayName(uri),
            bitsPerSample = metadata.bitsPerSample,
            encodingLabel = when (metadata.encoding) {
                WavEncoding.PCM_INTEGER -> "PCM"
                WavEncoding.IEEE_FLOAT -> "float"
            },
        )
        return LoadedSource(
            source = AudioSource(
                id = sourceId,
                location = uri.toString(),
                sampleRate = metadata.info.sampleRate,
                channelCount = metadata.info.channelCount,
                totalFrames = metadata.info.totalFrames,
            ),
            pyramid = pyramid,
            format = format,
        )
    }

    private fun decoderFor(source: AudioSource): WavDecoder = WavDecoder {
        contentResolver.openInputStream(Uri.parse(source.location))
            ?: error("Android could not reopen source audio")
    }

    private fun resetForLoad(message: String) {
        playbackEngine.stop()
        projectEditor = null
        waveformCache = null
        sourceFormats.clear()
        playbackLoaded = false
        exportInProgress = false
        importInProgress = false
        loadedChannelCount = 0
        loadedSourceTotalFrames = 0L
        timelineWindowStartFrame = 0L
        timelineWindowEndFrameExclusive = 0L
        selectionTimelineStartFrame = null
        selectionTimelineEndFrameExclusive = null
        lastShownPlaybackError = null
        statusText.text = message
        metadataText.text = ""
        gainSummaryText.text = "Clip gain: waiting for audio."
        waveformView.clearWaveform()
        updateSelectionUi()
        updateFileActionButtons()
        updatePlaybackUi()
    }

    private fun installLoadedProject(project: AudioProject, cache: InMemoryWaveformCache) {
        projectEditor = ProjectEditor(project)
        waveformCache = cache
        loadedSampleRate = project.timelineRate.hz
        loadedChannelCount = project.sources.values.firstOrNull()?.channelCount ?: 0
        loadedSourceTotalFrames = project.sources.values.maxOfOrNull { it.totalFrames } ?: 0L
    }

    private fun showLoadFailure(generation: Int, error: Throwable, resetQuickJoin: Boolean = false) {
        runOnUiThread {
            if (generation != loadGeneration.get() || isDestroyed) return@runOnUiThread
            if (resetQuickJoin) quickJoinStage = QuickJoinStage.IDLE
            projectEditor = null
            waveformCache = null
            sourceFormats.clear()
            playbackLoaded = false
            statusText.text = if (resetQuickJoin) {
                "Quick Join could not load A: ${error.message ?: error.javaClass.simpleName}"
            } else {
                "Could not load project audio."
            }
            metadataText.text = if (resetQuickJoin) "" else error.message ?: error.javaClass.simpleName
            gainSummaryText.text = "Clip gain: unavailable."
            waveformView.clearWaveform()
            updateSelectionUi()
            updateFileActionButtons()
            updatePlaybackUi()
        }
    }

    private fun bindEditorProject(
        statusMessage: String,
        preservedSelection: Pair<Long, Long>? = null,
    ) {
        val editor = projectEditor ?: return
        val project = editor.project
        val clips = project.tracks.single().clips.sortedBy { it.timelineStartFrame }
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
            channelCount = loadedChannelCount.coerceAtLeast(1),
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
        updateMetadataSummary(project)
        updateGainSummary(project)
        updateSelectionUi()
        updateFileActionButtons()
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
                gain = clip.gain,
            )
        }

    private fun updateMetadataSummary(project: AudioProject) {
        val clips = project.tracks.single().clips
        metadataText.text = buildString {
            append(project.title)
            append('\n')
            append(project.sources.size)
            append(if (project.sources.size == 1) " source • " else " sources • ")
            append(clips.size)
            append(if (clips.size == 1) " timeline clip" else " timeline clips")

            project.sources.values.forEachIndexed { index, source ->
                val format = sourceFormats[source.id]
                val channelText = when (source.channelCount) {
                    1 -> "mono"
                    2 -> "stereo"
                    else -> "${source.channelCount} channels"
                }
                append('\n')
                append(('A'.code + index).toChar())
                append(" • ")
                append(format?.displayName ?: "Source ${index + 1}")
                append(" • ")
                append(source.sampleRate.hz)
                append(" Hz • ")
                append(channelText)
                if (format != null) {
                    append(" • ")
                    append(format.bitsPerSample)
                    append("-bit ")
                    append(format.encodingLabel)
                }
            }
        }
    }

    private fun updateGainSummary(project: AudioProject) {
        val clips = project.tracks.single().clips.sortedBy { it.timelineStartFrame }
        gainSummaryText.text = if (clips.isEmpty()) {
            "Clip gain: no clips."
        } else {
            clips.mapIndexed { index, clip -> "C${index + 1} ${formatLinearGain(clip.gain)}" }
                .joinToString(prefix = "Clip gain: ", separator = " • ")
        }
    }

    private fun updateSelectionUi() {
        if (!::selectionText.isInitialized || !::trimButton.isInitialized || !::amplifyButton.isInitialized) return
        val editor = projectEditor
        val start = selectionTimelineStartFrame
        val end = selectionTimelineEndFrameExclusive

        if (editor == null || start == null || end == null || end <= start) {
            selectionText.text = interactionHint()
            trimButton.isEnabled = false
            splitButton.isEnabled = false
            deleteButton.isEnabled = false
            amplifyButton.isEnabled = false
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
        val trimTarget = project.clipContainingTimelineRange(TRACK_ID, start, end)
        trimButton.isEnabled = trimTarget != null &&
            (start != trimTarget.timelineStartFrame || end != project.clipTimelineEndFrameExclusive(trimTarget))
        splitButton.isEnabled = clips.isNotEmpty() && selectionCanCreateSplit(project, start, end)
        deleteButton.isEnabled = selectionOverlapsAudio(project, start, end)
        amplifyButton.isEnabled = selectionOverlapsAudio(project, start, end)
        updateHistoryButtons()
    }

    private fun updateFileActionButtons() {
        if (!::exportButton.isInitialized || !::addWavButton.isInitialized || !::quickJoinButton.isInitialized) return
        val hasProject = projectEditor != null
        val hasAudio = projectEditor?.project?.tracks?.singleOrNull()?.clips?.isNotEmpty() == true
        val quickJoinActive = quickJoinStage != QuickJoinStage.IDLE

        quickJoinButton.isEnabled = !importInProgress && !exportInProgress && !quickJoinActive
        quickJoinButton.text = when (quickJoinStage) {
            QuickJoinStage.IDLE -> "Quick Join"
            QuickJoinStage.PICKING_FIRST -> "Quick Join: choose A…"
            QuickJoinStage.LOADING_FIRST -> "Quick Join: loading A…"
            QuickJoinStage.PICKING_SECOND -> "Quick Join: choose B…"
            QuickJoinStage.LOADING_SECOND -> "Quick Join: loading B…"
        }

        addWavButton.isEnabled = hasProject && !importInProgress && !exportInProgress && !quickJoinActive
        addWavButton.text = if (importInProgress && !quickJoinActive) "Adding…" else "Add WAV"
        exportButton.isEnabled = hasAudio && !exportInProgress && !importInProgress && !quickJoinActive
        exportButton.text = if (exportInProgress) "Exporting…" else "Export WAV"
        updateHistoryButtons()
    }

    private fun updateHistoryButtons() {
        if (!::undoButton.isInitialized || !::redoButton.isInitialized) return
        val quickJoinActive = quickJoinStage != QuickJoinStage.IDLE
        undoButton.isEnabled = projectEditor?.canUndo == true && !importInProgress && !exportInProgress && !quickJoinActive
        redoButton.isEnabled = projectEditor?.canRedo == true && !importInProgress && !exportInProgress && !quickJoinActive
    }

    private fun updatePlaybackUi() {
        if (!::playPauseButton.isInitialized) return
        playPauseButton.isEnabled = playbackLoaded && !exportInProgress
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

        val frame = playbackEngine.playheadFrame.coerceIn(timelineWindowStartFrame, timelineWindowEndFrameExclusive)
        waveformView.setPlayheadFrame(frame)
        timeText.text = "${formatDuration(frame - timelineWindowStartFrame, loadedSampleRate)} / ${formatDuration(durationFrames, loadedSampleRate)}"

        val error = playbackEngine.lastError
        if (error != null && error != lastShownPlaybackError) {
            lastShownPlaybackError = error
            statusText.text = "Playback error: $error"
        }
    }

    private fun selectionCanCreateSplit(project: AudioProject, start: Long, end: Long): Boolean =
        project.tracks.single().clips.any { clip ->
            val clipStart = clip.timelineStartFrame
            val clipEnd = project.clipTimelineEndFrameExclusive(clip)
            (start > clipStart && start < clipEnd) || (end > clipStart && end < clipEnd)
        }

    private fun selectionOverlapsAudio(project: AudioProject, start: Long, end: Long): Boolean =
        project.tracks.single().clips.any { clip ->
            maxOf(start, clip.timelineStartFrame) < minOf(end, project.clipTimelineEndFrameExclusive(clip))
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

    private fun formatDb(decibels: Float): String = String.format(Locale.US, "%+.1f dB", decibels)

    private fun formatLinearGain(gain: Float): String {
        val db = linearGainToDecibels(gain)
        return if (db.isFinite()) formatDb(db) else "-∞ dB"
    }

    private fun newClipId(): String = "clip-${UUID.randomUUID()}"

    private fun newSourceId(): String = "source-${UUID.randomUUID()}"

    private fun clearRestoredState() {
        restoredSourceIds = null
        restoredSourceLocations = null
        restoredClipIds = null
        restoredClipSourceIds = null
        restoredSourceStarts = null
        restoredSourceEnds = null
        restoredTimelineStarts = null
        restoredGains = null
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    override fun onStop() {
        if (::playbackEngine.isInitialized && playbackEngine.state == PlaybackState.PLAYING) {
            playbackEngine.pause()
        }
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_QUICK_JOIN_STAGE, quickJoinStage.name)
        projectEditor?.project?.let { project ->
            val sources = project.sources.values.toList()
            outState.putStringArrayList(STATE_SOURCE_IDS, ArrayList(sources.map { it.id }))
            outState.putStringArrayList(STATE_SOURCE_LOCATIONS, ArrayList(sources.map { it.location }))

            val clips = project.tracks.singleOrNull()?.clips.orEmpty()
            outState.putStringArrayList(STATE_CLIP_IDS, ArrayList(clips.map { it.id }))
            outState.putStringArrayList(STATE_CLIP_SOURCE_IDS, ArrayList(clips.map { it.sourceId }))
            outState.putLongArray(STATE_CLIP_SOURCE_STARTS, clips.map { it.sourceRange.startFrame }.toLongArray())
            outState.putLongArray(STATE_CLIP_SOURCE_ENDS, clips.map { it.sourceRange.endFrameExclusive }.toLongArray())
            outState.putLongArray(STATE_CLIP_TIMELINE_STARTS, clips.map { it.timelineStartFrame }.toLongArray())
            outState.putFloatArray(STATE_CLIP_GAINS, clips.map { it.gain }.toFloatArray())
        }
        super.onSaveInstanceState(outState)
    }

    override fun onRetainNonConfigurationInstance(): Any? {
        val editor = projectEditor ?: return null
        val cache = waveformCache ?: return null
        return RetainedSession(
            editor = editor,
            waveformCache = cache,
            sourceFormats = LinkedHashMap(sourceFormats),
            loadedSourceTotalFrames = loadedSourceTotalFrames,
            selectionStart = selectionTimelineStartFrame,
            selectionEnd = selectionTimelineEndFrameExclusive,
            quickJoinStage = quickJoinStage,
        )
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
        private const val REQUEST_EXPORT_WAV = 2002
        private const val REQUEST_ADD_WAV = 2003
        private const val REQUEST_QUICK_JOIN_FIRST = 2004
        private const val REQUEST_QUICK_JOIN_SECOND = 2005
        private const val STATE_SOURCE_IDS = "source_ids"
        private const val STATE_SOURCE_LOCATIONS = "source_locations"
        private const val STATE_CLIP_IDS = "clip_ids"
        private const val STATE_CLIP_SOURCE_IDS = "clip_source_ids"
        private const val STATE_CLIP_SOURCE_STARTS = "clip_source_starts"
        private const val STATE_CLIP_SOURCE_ENDS = "clip_source_ends"
        private const val STATE_CLIP_TIMELINE_STARTS = "clip_timeline_starts"
        private const val STATE_CLIP_GAINS = "clip_gains"
        private const val STATE_QUICK_JOIN_STAGE = "quick_join_stage"
        private const val TRACK_ID = "track-1"
        private const val HIGH_GAIN_WARNING_DB = 24f
    }
}
