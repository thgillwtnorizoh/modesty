package io.github.thgillwtnorizoh.modesty.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import io.github.thgillwtnorizoh.modesty.core.waveform.WaveformCache
import kotlin.math.roundToInt
import kotlin.math.roundToLong

data class TimelineWaveformClip(
    val id: String,
    val sourceId: String,
    val sourceStartFrame: Long,
    val sourceEndFrameExclusive: Long,
    val timelineStartFrame: Long,
    val timelineEndFrameExclusive: Long,
) {
    init {
        require(id.isNotBlank())
        require(sourceId.isNotBlank())
        require(sourceStartFrame >= 0)
        require(sourceEndFrameExclusive > sourceStartFrame)
        require(timelineStartFrame >= 0)
        require(timelineEndFrameExclusive > timelineStartFrame)
    }
}

class WaveformView(context: Context) : View(context) {
    private val waveformPaint = Paint().apply {
        color = Color.rgb(35, 35, 35)
        strokeWidth = 1f
        isAntiAlias = false
    }
    private val guidePaint = Paint().apply {
        color = Color.rgb(190, 190, 190)
        strokeWidth = 1f
    }
    private val clipBoundaryPaint = Paint().apply {
        color = Color.rgb(110, 130, 155)
        strokeWidth = resources.displayMetrics.density
        isAntiAlias = false
    }
    private val selectionPaint = Paint().apply {
        color = Color.argb(56, 55, 120, 215)
        style = Paint.Style.FILL
    }
    private val selectionEdgePaint = Paint().apply {
        color = Color.rgb(55, 105, 185)
        strokeWidth = 2f * resources.displayMetrics.density
        isAntiAlias = false
    }
    private val playheadPaint = Paint().apply {
        color = Color.rgb(205, 45, 45)
        strokeWidth = 2f * resources.displayMetrics.density
        isAntiAlias = false
    }
    private val textPaint = Paint().apply {
        color = Color.rgb(100, 100, 100)
        textSize = 14f * resources.displayMetrics.scaledDensity
        isAntiAlias = true
    }
    private val clipTextPaint = Paint().apply {
        color = Color.rgb(90, 105, 125)
        textSize = 11f * resources.displayMetrics.scaledDensity
        isAntiAlias = true
    }

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var cache: WaveformCache? = null
    private var channelCount: Int = 0
    private var clips: List<TimelineWaveformClip> = emptyList()
    private var visibleTimelineStartFrame: Long = 0
    private var visibleTimelineEndFrameExclusive: Long = 0
    private var playheadFrame: Long = 0

    private var selectionStartTimelineFrame: Long? = null
    private var selectionEndTimelineFrameExclusive: Long? = null
    private var downX: Float = 0f
    private var downY: Float = 0f
    private var gestureIntent = WaveformGestureIntent.UNDECIDED
    private var selectionBeforeGestureStart: Long? = null
    private var selectionBeforeGestureEnd: Long? = null

    var onSeekRequested: ((Long) -> Unit)? = null
    var onSelectionChanged: ((Long?, Long?) -> Unit)? = null

    fun setTimeline(
        cache: WaveformCache,
        channelCount: Int,
        clips: List<TimelineWaveformClip>,
        visibleTimelineStartFrame: Long,
        visibleTimelineEndFrameExclusive: Long,
    ) {
        require(channelCount > 0)
        require(visibleTimelineStartFrame >= 0)
        require(visibleTimelineEndFrameExclusive > visibleTimelineStartFrame)

        this.cache = cache
        this.channelCount = channelCount
        this.clips = clips.sortedBy { it.timelineStartFrame }
        this.visibleTimelineStartFrame = visibleTimelineStartFrame
        this.visibleTimelineEndFrameExclusive = visibleTimelineEndFrameExclusive
        playheadFrame = visibleTimelineStartFrame
        clearSelectionInternal(notify = false)
        invalidate()
    }

    fun setSelection(
        startTimelineFrame: Long,
        endTimelineFrameExclusive: Long,
        notify: Boolean = true,
    ) {
        val start = startTimelineFrame.coerceIn(visibleTimelineStartFrame, visibleTimelineEndFrameExclusive)
        val end = endTimelineFrameExclusive.coerceIn(visibleTimelineStartFrame, visibleTimelineEndFrameExclusive)
        if (end <= start) {
            clearSelectionInternal(notify)
            return
        }
        selectionStartTimelineFrame = start
        selectionEndTimelineFrameExclusive = end
        if (notify) onSelectionChanged?.invoke(start, end)
        invalidate()
    }

    fun setPlayheadFrame(frame: Long) {
        val clamped = frame.coerceIn(visibleTimelineStartFrame, visibleTimelineEndFrameExclusive)
        if (clamped == playheadFrame) return
        playheadFrame = clamped
        invalidate()
    }

    fun clearSelection() {
        clearSelectionInternal(notify = true)
    }

    fun clearWaveform() {
        cache = null
        channelCount = 0
        clips = emptyList()
        visibleTimelineStartFrame = 0
        visibleTimelineEndFrameExclusive = 0
        playheadFrame = 0
        clearSelectionInternal(notify = false)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(247, 247, 247))

        val localCache = cache
        val timelineLength = visibleTimelineEndFrameExclusive - visibleTimelineStartFrame
        if (localCache == null || channelCount <= 0 || timelineLength <= 0) {
            canvas.drawText("No waveform loaded", paddingLeft + 12f, height / 2f, textPaint)
            return
        }

        val drawableWidth = (width - paddingLeft - paddingRight).coerceAtLeast(1)
        val drawableHeight = (height - paddingTop - paddingBottom).coerceAtLeast(1)
        val channelHeight = drawableHeight.toFloat() / channelCount

        for (channel in 0 until channelCount) {
            val centerY = paddingTop + channel * channelHeight + channelHeight / 2f
            canvas.drawLine(
                paddingLeft.toFloat(),
                centerY,
                (width - paddingRight).toFloat(),
                centerY,
                guidePaint,
            )
        }

        clips.forEachIndexed { clipIndex, clip ->
            drawClip(canvas, localCache, clip, clipIndex, drawableWidth, channelHeight)
        }

        if (clips.isEmpty()) {
            canvas.drawText(
                "No clips remain. Undo can bring them back.",
                paddingLeft + 12f,
                height / 2f,
                textPaint,
            )
        }

        val selectionStart = selectionStartTimelineFrame
        val selectionEnd = selectionEndTimelineFrameExclusive
        if (selectionStart != null && selectionEnd != null && selectionEnd > selectionStart) {
            val left = xForTimelineFrame(selectionStart)
            val right = xForTimelineFrame(selectionEnd)
            canvas.drawRect(
                left,
                paddingTop.toFloat(),
                right,
                (height - paddingBottom).toFloat(),
                selectionPaint,
            )
            canvas.drawLine(left, paddingTop.toFloat(), left, (height - paddingBottom).toFloat(), selectionEdgePaint)
            canvas.drawLine(right, paddingTop.toFloat(), right, (height - paddingBottom).toFloat(), selectionEdgePaint)
        }

        val playheadX = xForTimelineFrame(playheadFrame)
        canvas.drawLine(
            playheadX,
            paddingTop.toFloat(),
            playheadX,
            (height - paddingBottom).toFloat(),
            playheadPaint,
        )
    }

    private fun drawClip(
        canvas: Canvas,
        localCache: WaveformCache,
        clip: TimelineWaveformClip,
        clipIndex: Int,
        drawableWidth: Int,
        channelHeight: Float,
    ) {
        val overlapStart = maxOf(clip.timelineStartFrame, visibleTimelineStartFrame)
        val overlapEnd = minOf(clip.timelineEndFrameExclusive, visibleTimelineEndFrameExclusive)
        if (overlapEnd <= overlapStart) return

        val clipTimelineLength = clip.timelineEndFrameExclusive - clip.timelineStartFrame
        val clipSourceLength = clip.sourceEndFrameExclusive - clip.sourceStartFrame
        val startFraction = (overlapStart - clip.timelineStartFrame).toDouble() / clipTimelineLength.toDouble()
        val endFraction = (overlapEnd - clip.timelineStartFrame).toDouble() / clipTimelineLength.toDouble()
        val sourceStart = (clip.sourceStartFrame + startFraction * clipSourceLength).roundToLong()
            .coerceIn(clip.sourceStartFrame, clip.sourceEndFrameExclusive - 1)
        val sourceEnd = (clip.sourceStartFrame + endFraction * clipSourceLength).roundToLong()
            .coerceIn(sourceStart + 1, clip.sourceEndFrameExclusive)

        val left = xForTimelineFrame(overlapStart)
        val right = xForTimelineFrame(overlapEnd)
        val pixelWidth = (right - left).roundToInt().coerceAtLeast(1).coerceAtMost(drawableWidth)

        for (channel in 0 until channelCount) {
            val centerY = paddingTop + channel * channelHeight + channelHeight / 2f
            val amplitudeHeight = channelHeight * 0.45f
            val buckets = localCache.read(
                sourceId = clip.sourceId,
                channel = channel,
                startSourceFrame = sourceStart,
                endSourceFrameExclusive = sourceEnd,
                bucketCount = pixelWidth,
            )
            if (buckets.isEmpty()) continue

            val xStep = (right - left) / buckets.size
            buckets.forEachIndexed { index, bucket ->
                val x = left + (index + 0.5f) * xStep
                val min = bucket.min.coerceIn(-1f, 1f)
                val max = bucket.max.coerceIn(-1f, 1f)
                val yTop = centerY - max * amplitudeHeight
                val yBottom = centerY - min * amplitudeHeight
                canvas.drawLine(x, yTop, x, yBottom, waveformPaint)
            }
        }

        val boundaryTop = paddingTop.toFloat()
        val boundaryBottom = (height - paddingBottom).toFloat()
        canvas.drawLine(left, boundaryTop, left, boundaryBottom, clipBoundaryPaint)
        canvas.drawLine(right, boundaryTop, right, boundaryBottom, clipBoundaryPaint)
        if (right - left > 34f * resources.displayMetrics.density) {
            canvas.drawText(
                "C${clipIndex + 1}",
                left + 4f * resources.displayMetrics.density,
                paddingTop + clipTextPaint.textSize + 2f,
                clipTextPaint,
            )
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (cache == null || visibleTimelineEndFrameExclusive <= visibleTimelineStartFrame) return false

        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                gestureIntent = WaveformGestureIntent.UNDECIDED
                selectionBeforeGestureStart = selectionStartTimelineFrame
                selectionBeforeGestureEnd = selectionEndTimelineFrameExclusive
                parent?.requestDisallowInterceptTouchEvent(true)
                true
            }

            MotionEvent.ACTION_MOVE -> {
                if (gestureIntent == WaveformGestureIntent.UNDECIDED) {
                    gestureIntent = WaveformGestureArbiter.classify(
                        deltaX = event.x - downX,
                        deltaY = event.y - downY,
                        touchSlop = touchSlop,
                    )
                    if (gestureIntent == WaveformGestureIntent.SCROLL) {
                        parent?.requestDisallowInterceptTouchEvent(false)
                    }
                }

                when (gestureIntent) {
                    WaveformGestureIntent.SELECT -> {
                        parent?.requestDisallowInterceptTouchEvent(true)
                        updateSelectionFromXs(downX, event.x, notify = true)
                    }
                    WaveformGestureIntent.SCROLL -> parent?.requestDisallowInterceptTouchEvent(false)
                    WaveformGestureIntent.UNDECIDED -> Unit
                }
                true
            }

            MotionEvent.ACTION_UP -> {
                when (gestureIntent) {
                    WaveformGestureIntent.SELECT -> updateSelectionFromXs(downX, event.x, notify = true)
                    WaveformGestureIntent.SCROLL -> Unit
                    WaveformGestureIntent.UNDECIDED -> {
                        clearSelectionInternal(notify = true)
                        val frame = timelineFrameAtX(event.x)
                        setPlayheadFrame(frame)
                        onSeekRequested?.invoke(frame)
                        performClick()
                    }
                }
                parent?.requestDisallowInterceptTouchEvent(false)
                gestureIntent = WaveformGestureIntent.UNDECIDED
                true
            }

            MotionEvent.ACTION_CANCEL -> {
                restoreSelectionBeforeGesture()
                parent?.requestDisallowInterceptTouchEvent(false)
                gestureIntent = WaveformGestureIntent.UNDECIDED
                true
            }

            else -> true
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun updateSelectionFromXs(firstX: Float, secondX: Float, notify: Boolean) {
        val first = timelineFrameAtX(firstX)
        val second = timelineFrameAtX(secondX)
        val low = minOf(first, second)
        val high = maxOf(first, second)

        if (high <= low) {
            clearSelectionInternal(notify)
            return
        }

        selectionStartTimelineFrame = low
        selectionEndTimelineFrameExclusive = high
        if (notify) onSelectionChanged?.invoke(low, high)
        invalidate()
    }

    private fun restoreSelectionBeforeGesture() {
        val start = selectionBeforeGestureStart
        val end = selectionBeforeGestureEnd
        if (selectionStartTimelineFrame == start && selectionEndTimelineFrameExclusive == end) return

        selectionStartTimelineFrame = start
        selectionEndTimelineFrameExclusive = end
        onSelectionChanged?.invoke(start, end)
        invalidate()
    }

    private fun clearSelectionInternal(notify: Boolean) {
        val changed = selectionStartTimelineFrame != null || selectionEndTimelineFrameExclusive != null
        selectionStartTimelineFrame = null
        selectionEndTimelineFrameExclusive = null
        if (changed) invalidate()
        if (notify) onSelectionChanged?.invoke(null, null)
    }

    private fun timelineFrameAtX(x: Float): Long {
        val fraction = fractionAtX(x)
        val length = visibleTimelineEndFrameExclusive - visibleTimelineStartFrame
        return (visibleTimelineStartFrame + fraction * length.toDouble()).roundToLong()
            .coerceIn(visibleTimelineStartFrame, visibleTimelineEndFrameExclusive)
    }

    private fun xForTimelineFrame(frame: Long): Float {
        val drawableWidth = (width - paddingLeft - paddingRight).coerceAtLeast(1)
        val length = (visibleTimelineEndFrameExclusive - visibleTimelineStartFrame).coerceAtLeast(1)
        val fraction = (frame - visibleTimelineStartFrame).toDouble() / length.toDouble()
        return paddingLeft + (fraction.coerceIn(0.0, 1.0) * drawableWidth).toFloat()
    }

    private fun fractionAtX(x: Float): Double {
        val drawableWidth = (width - paddingLeft - paddingRight).coerceAtLeast(1)
        val relativeX = (x - paddingLeft).coerceIn(0f, drawableWidth.toFloat())
        return relativeX.toDouble() / drawableWidth.toDouble()
    }
}
