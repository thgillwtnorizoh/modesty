package io.github.thgillwtnorizoh.modesty.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import io.github.thgillwtnorizoh.modesty.core.waveform.WaveformCache
import kotlin.math.abs
import kotlin.math.roundToLong

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

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var cache: WaveformCache? = null
    private var sourceId: String? = null
    private var sourceTotalFrames: Long = 0
    private var channelCount: Int = 0

    private var visibleSourceStartFrame: Long = 0
    private var visibleSourceEndFrameExclusive: Long = 0
    private var timelineStartFrame: Long = 0
    private var timelineEndFrameExclusive: Long = 0
    private var playheadFrame: Long = 0

    private var selectionStartSourceFrame: Long? = null
    private var selectionEndSourceFrameExclusive: Long? = null
    private var downX: Float = 0f
    private var draggingSelection = false

    var onSeekRequested: ((Long) -> Unit)? = null
    var onSelectionChanged: ((Long?, Long?) -> Unit)? = null

    fun setWaveform(
        cache: WaveformCache,
        sourceId: String,
        totalFrames: Long,
        channelCount: Int,
    ) {
        require(totalFrames > 0)
        require(channelCount > 0)
        this.cache = cache
        this.sourceId = sourceId
        sourceTotalFrames = totalFrames
        this.channelCount = channelCount
        visibleSourceStartFrame = 0
        visibleSourceEndFrameExclusive = totalFrames
        timelineStartFrame = 0
        timelineEndFrameExclusive = totalFrames
        playheadFrame = 0
        clearSelectionInternal(notify = false)
        invalidate()
    }

    /**
     * Makes one clip range the visible editing window without rebuilding the waveform cache.
     * Source and timeline ranges are supplied separately so the view does not assume they will
     * remain 1:1 once resampling enters the engine later.
     */
    fun setClipWindow(
        sourceStartFrame: Long,
        sourceEndFrameExclusive: Long,
        timelineStartFrame: Long,
        timelineEndFrameExclusive: Long,
    ) {
        require(sourceStartFrame >= 0)
        require(sourceEndFrameExclusive > sourceStartFrame)
        require(sourceEndFrameExclusive <= sourceTotalFrames)
        require(timelineStartFrame >= 0)
        require(timelineEndFrameExclusive > timelineStartFrame)

        visibleSourceStartFrame = sourceStartFrame
        visibleSourceEndFrameExclusive = sourceEndFrameExclusive
        this.timelineStartFrame = timelineStartFrame
        this.timelineEndFrameExclusive = timelineEndFrameExclusive
        playheadFrame = timelineStartFrame
        clearSelectionInternal(notify = false)
        invalidate()
    }

    fun setPlayheadFrame(frame: Long) {
        val clamped = frame.coerceIn(timelineStartFrame, timelineEndFrameExclusive)
        if (clamped == playheadFrame) return
        playheadFrame = clamped
        invalidate()
    }

    fun clearSelection() {
        clearSelectionInternal(notify = true)
    }

    fun clearWaveform() {
        cache = null
        sourceId = null
        sourceTotalFrames = 0
        channelCount = 0
        visibleSourceStartFrame = 0
        visibleSourceEndFrameExclusive = 0
        timelineStartFrame = 0
        timelineEndFrameExclusive = 0
        playheadFrame = 0
        clearSelectionInternal(notify = false)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(247, 247, 247))

        val localCache = cache
        val localSourceId = sourceId
        val visibleSourceFrames = visibleSourceEndFrameExclusive - visibleSourceStartFrame
        val visibleTimelineFrames = timelineEndFrameExclusive - timelineStartFrame
        if (
            localCache == null ||
            localSourceId == null ||
            sourceTotalFrames <= 0 ||
            channelCount <= 0 ||
            visibleSourceFrames <= 0 ||
            visibleTimelineFrames <= 0
        ) {
            canvas.drawText("No waveform loaded", paddingLeft + 12f, height / 2f, textPaint)
            return
        }

        val drawableWidth = (width - paddingLeft - paddingRight).coerceAtLeast(1)
        val drawableHeight = (height - paddingTop - paddingBottom).coerceAtLeast(1)
        val channelHeight = drawableHeight.toFloat() / channelCount

        for (channel in 0 until channelCount) {
            val channelTop = paddingTop + channel * channelHeight
            val centerY = channelTop + channelHeight / 2f
            val amplitudeHeight = channelHeight * 0.45f
            canvas.drawLine(
                paddingLeft.toFloat(),
                centerY,
                (width - paddingRight).toFloat(),
                centerY,
                guidePaint,
            )

            val buckets = localCache.read(
                sourceId = localSourceId,
                channel = channel,
                startSourceFrame = visibleSourceStartFrame,
                endSourceFrameExclusive = visibleSourceEndFrameExclusive,
                bucketCount = drawableWidth,
            )
            if (buckets.isEmpty()) continue

            val xStep = drawableWidth.toFloat() / buckets.size
            buckets.forEachIndexed { index, bucket ->
                val x = paddingLeft + (index + 0.5f) * xStep
                val min = bucket.min.coerceIn(-1f, 1f)
                val max = bucket.max.coerceIn(-1f, 1f)
                val yTop = centerY - max * amplitudeHeight
                val yBottom = centerY - min * amplitudeHeight
                canvas.drawLine(x, yTop, x, yBottom, waveformPaint)
            }

            if (channelCount > 1) {
                canvas.drawText(
                    "CH ${channel + 1}",
                    paddingLeft + 8f,
                    channelTop + textPaint.textSize + 4f,
                    textPaint,
                )
            }
        }

        val selectionStart = selectionStartSourceFrame
        val selectionEnd = selectionEndSourceFrameExclusive
        if (selectionStart != null && selectionEnd != null && selectionEnd > selectionStart) {
            val left = xForSourceFrame(selectionStart)
            val right = xForSourceFrame(selectionEnd)
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

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (cache == null || visibleSourceEndFrameExclusive <= visibleSourceStartFrame) return false

        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                draggingSelection = false
                true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!draggingSelection && abs(event.x - downX) >= touchSlop) {
                    draggingSelection = true
                }
                if (draggingSelection) updateSelectionFromXs(downX, event.x, notify = true)
                true
            }

            MotionEvent.ACTION_UP -> {
                if (draggingSelection) {
                    updateSelectionFromXs(downX, event.x, notify = true)
                    draggingSelection = false
                } else {
                    clearSelectionInternal(notify = true)
                    val frame = timelineFrameAtX(event.x)
                    setPlayheadFrame(frame)
                    onSeekRequested?.invoke(frame)
                    performClick()
                }
                true
            }

            MotionEvent.ACTION_CANCEL -> {
                draggingSelection = false
                clearSelectionInternal(notify = true)
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
        val first = sourceFrameAtX(firstX)
        val second = sourceFrameAtX(secondX)
        val low = minOf(first, second)
        val high = maxOf(first, second)

        if (high <= low) {
            clearSelectionInternal(notify)
            return
        }

        selectionStartSourceFrame = low
        selectionEndSourceFrameExclusive = high
        if (notify) onSelectionChanged?.invoke(low, high)
        invalidate()
    }

    private fun clearSelectionInternal(notify: Boolean) {
        val changed = selectionStartSourceFrame != null || selectionEndSourceFrameExclusive != null
        selectionStartSourceFrame = null
        selectionEndSourceFrameExclusive = null
        if (changed) invalidate()
        if (notify) onSelectionChanged?.invoke(null, null)
    }

    private fun sourceFrameAtX(x: Float): Long {
        val fraction = fractionAtX(x)
        val length = visibleSourceEndFrameExclusive - visibleSourceStartFrame
        return (visibleSourceStartFrame + fraction * length.toDouble()).roundToLong()
            .coerceIn(visibleSourceStartFrame, visibleSourceEndFrameExclusive)
    }

    private fun timelineFrameAtX(x: Float): Long {
        val fraction = fractionAtX(x)
        val length = timelineEndFrameExclusive - timelineStartFrame
        return (timelineStartFrame + fraction * length.toDouble()).roundToLong()
            .coerceIn(timelineStartFrame, timelineEndFrameExclusive)
    }

    private fun xForSourceFrame(frame: Long): Float {
        val drawableWidth = (width - paddingLeft - paddingRight).coerceAtLeast(1)
        val length = (visibleSourceEndFrameExclusive - visibleSourceStartFrame).coerceAtLeast(1)
        val fraction = (frame - visibleSourceStartFrame).toDouble() / length.toDouble()
        return paddingLeft + (fraction.coerceIn(0.0, 1.0) * drawableWidth).toFloat()
    }

    private fun xForTimelineFrame(frame: Long): Float {
        val drawableWidth = (width - paddingLeft - paddingRight).coerceAtLeast(1)
        val length = (timelineEndFrameExclusive - timelineStartFrame).coerceAtLeast(1)
        val fraction = (frame - timelineStartFrame).toDouble() / length.toDouble()
        return paddingLeft + (fraction.coerceIn(0.0, 1.0) * drawableWidth).toFloat()
    }

    private fun fractionAtX(x: Float): Double {
        val drawableWidth = (width - paddingLeft - paddingRight).coerceAtLeast(1)
        val relativeX = (x - paddingLeft).coerceIn(0f, drawableWidth.toFloat())
        return relativeX.toDouble() / drawableWidth.toDouble()
    }
}
