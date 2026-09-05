package io.github.thgillwtnorizoh.modesty.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import io.github.thgillwtnorizoh.modesty.core.waveform.WaveformCache

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
    private val textPaint = Paint().apply {
        color = Color.rgb(100, 100, 100)
        textSize = 14f * resources.displayMetrics.scaledDensity
        isAntiAlias = true
    }

    private var cache: WaveformCache? = null
    private var sourceId: String? = null
    private var totalFrames: Long = 0
    private var channelCount: Int = 0

    fun setWaveform(
        cache: WaveformCache,
        sourceId: String,
        totalFrames: Long,
        channelCount: Int,
    ) {
        this.cache = cache
        this.sourceId = sourceId
        this.totalFrames = totalFrames
        this.channelCount = channelCount
        invalidate()
    }

    fun clearWaveform() {
        cache = null
        sourceId = null
        totalFrames = 0
        channelCount = 0
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(247, 247, 247))

        val localCache = cache
        val localSourceId = sourceId
        if (localCache == null || localSourceId == null || totalFrames <= 0 || channelCount <= 0) {
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
                startSourceFrame = 0,
                endSourceFrameExclusive = totalFrames,
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
    }
}
