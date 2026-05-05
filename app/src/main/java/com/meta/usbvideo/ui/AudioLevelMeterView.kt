package com.meta.usbvideo.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Multi-channel audio level meter supporting 2.0 / 5.1 / 7.1.
 * Displays horizontal bars with channel labels (FL, FR, FC, LFE, SL, SR, BL, BR).
 * The number of bars dynamically matches the channel count from setLevels().
 */
class AudioLevelMeterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // Current levels array — size = active channel count
    private var levels = floatArrayOf(0f, 0f)
    private var channelCount = 2

    // Channel labels in standard order: FL FR FC LFE SL SR BL BR
    companion object {
        private const val BAR_GAP_DP = 1.5f
        private const val BAR_HEIGHT_DP = 6f
        private const val LABEL_WIDTH_DP = 16f
        private val CHANNEL_LABELS_8 = arrayOf("FL", "FR", "FC", "LFE", "SL", "SR", "BL", "BR")
        private val CHANNEL_LABELS_6 = arrayOf("FL", "FR", "FC", "LFE", "SL", "SR")
        private val CHANNEL_LABELS_2 = arrayOf("L", "R")
    }

    // Dark track background
    private val trackPaint = Paint().apply {
        color = Color.parseColor("#2a2a2a")
        style = Paint.Style.FILL
    }

    // Level fill — green for normal, yellow for high, red for peak
    private val fillPaintGreen = Paint().apply {
        color = Color.parseColor("#66bb6a")
        style = Paint.Style.FILL
    }
    private val fillPaintYellow = Paint().apply {
        color = Color.parseColor("#fdd835")
        style = Paint.Style.FILL
    }
    private val fillPaintRed = Paint().apply {
        color = Color.parseColor("#ef5350")
        style = Paint.Style.FILL
    }

    // Thin border for the track
    private val borderPaint = Paint().apply {
        color = Color.parseColor("#444444")
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }

    // Label text — sized to fit within bar height
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#cccccc")
        textSize = 7f * resources.displayMetrics.scaledDensity
        typeface = android.graphics.Typeface.MONOSPACE
    }

    private val rect = RectF()

    /**
     * Update bar levels. Fills data into existing channel slots.
     * If newLevels has fewer elements than channelCount, remaining channels show 0.
     * Channel count is only changed via setChannelCount().
     */
    fun setLevels(newLevels: FloatArray) {
        if (newLevels.isEmpty()) return
        for (i in 0 until channelCount) {
            levels[i] = if (i < newLevels.size) newLevels[i].coerceIn(0f, 1f) else 0f
        }
        invalidate()
    }

    /** Explicitly set channel count (call before streaming starts). */
    fun setChannelCount(count: Int) {
        val c = count.coerceIn(2, 8)
        if (channelCount != c) {
            channelCount = c
            levels = FloatArray(c)
            requestLayout()
            invalidate()
        }
    }

    private fun getLabels(): Array<String> = when {
        channelCount >= 8 -> CHANNEL_LABELS_8
        channelCount >= 6 -> CHANNEL_LABELS_6
        else -> CHANNEL_LABELS_2
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val density = resources.displayMetrics.density
        val gap = BAR_GAP_DP * density
        val barH = BAR_HEIGHT_DP * density
        val desiredHeight = (gap + (barH + gap) * channelCount).toInt()
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, desiredHeight)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val density = resources.displayMetrics.density
        val gap = BAR_GAP_DP * density
        val barH = BAR_HEIGHT_DP * density
        val labelW = LABEL_WIDTH_DP * density
        val barLeft = labelW + 2f * density
        val labels = getLabels()

        // Pre-compute text vertical center offset
        val fontMetrics = labelPaint.fontMetrics
        val textCenterOffset = ((-fontMetrics.top - fontMetrics.bottom) / 2f)

        for (i in 0 until channelCount) {
            val top = gap + i * (barH + gap)
            val bottom = top + barH
            val centerY = (top + bottom) / 2f

            // Channel label — vertically centered with bar
            if (i < labels.size) {
                canvas.drawText(labels[i], 0f, centerY + textCenterOffset, labelPaint)
            }

            // Track (dark background)
            rect.set(barLeft, top, w, bottom)
            canvas.drawRect(rect, trackPaint)

            // Border
            canvas.drawRect(rect, borderPaint)

            // Level fill with color zones
            val level = if (i < levels.size) levels[i] else 0f
            if (level > 0.005f) {
                val barW = w - barLeft
                val levelRight = barLeft + barW * level

                // Green zone: 0 – 0.7
                val greenEnd = barLeft + barW * level.coerceAtMost(0.7f)
                rect.set(barLeft, top, greenEnd, bottom)
                canvas.drawRect(rect, fillPaintGreen)

                // Yellow zone: 0.7 – 0.9
                if (level > 0.7f) {
                    val yellowStart = barLeft + barW * 0.7f
                    val yellowEnd = barLeft + barW * level.coerceAtMost(0.9f)
                    rect.set(yellowStart, top, yellowEnd, bottom)
                    canvas.drawRect(rect, fillPaintYellow)
                }

                // Red zone: 0.9 – 1.0
                if (level > 0.9f) {
                    val redStart = barLeft + barW * 0.9f
                    rect.set(redStart, top, levelRight, bottom)
                    canvas.drawRect(rect, fillPaintRed)
                }
            }
        }
    }
}
