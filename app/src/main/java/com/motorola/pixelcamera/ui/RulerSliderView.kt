package com.motorola.pixelcamera.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Google Camera's manual-control slider: a ruler of ticks that slides under a fixed blue marker
 * in the middle. Ticks are 8dp apart, every fourth one taller and blue, fading towards the ends.
 * With few values each value gets four ticks, so short scales still look like a ruler.
 */
class RulerSliderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var max: Int = 100
        set(value) {
            field = value.coerceAtLeast(1)
            if (progress > field) progress = field
            invalidate()
        }

    var progress: Int = 0
        set(value) {
            field = value.coerceIn(0, max)
            shown = field.toFloat()
            invalidate()
        }

    /** Called only when the user moves the ruler. */
    var onUserChange: ((Int) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val tickGap = 8f * density
    private val tickWidth = 1.6f * density
    private val minorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE3E3E3.toInt() }
    private val majorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFA8C7FA.toInt() }

    /** Where the ruler is drawn, in values; follows the finger smoothly while dragging. */
    private var shown = 0f
    private var downX = 0f
    private var downValue = 0f

    private val ticksPerValue get() = ticksPerValue(max)

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val base = height / 2f + 6f * density
        val half = width / 2f
        val ticks = max * ticksPerValue
        val first = ((shown * ticksPerValue) - half / tickGap).toInt().coerceAtLeast(0)
        val last = ((shown * ticksPerValue) + half / tickGap).toInt().coerceAtMost(ticks)
        for (t in first..last) {
            val x = cx + (t - shown * ticksPerValue) * tickGap
            val fade = (1f - abs(x - cx) / half).coerceIn(0f, 1f)
            val major = t % 4 == 0
            val paint = if (major) majorPaint else minorPaint
            paint.alpha = (255 * (0.25f + 0.75f * fade)).toInt()
            val h = (if (major) 12f else 6f) * density
            canvas.drawRect(x - tickWidth / 2, base - h, x + tickWidth / 2, base, paint)
        }
        majorPaint.alpha = 255
        canvas.drawRect(cx - 1.8f * density, base - 16f * density, cx + 1.8f * density, base, majorPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                downX = event.x
                downValue = progress.toFloat()
            }
            MotionEvent.ACTION_MOVE -> {
                shown = valueAfterDrag(downValue, event.x - downX, tickGap * ticksPerValue, max)
                val step = shown.roundToInt()
                if (step != progress) {
                    progress = step
                    shown = valueAfterDrag(downValue, event.x - downX, tickGap * ticksPerValue, max)
                    onUserChange?.invoke(step)
                }
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                shown = progress.toFloat()
                invalidate()
            }
        }
        return true
    }

    /** Moves one value along, as the end icons do. */
    fun nudge(by: Int) {
        val next = (progress + by).coerceIn(0, max)
        if (next != progress) {
            progress = next
            onUserChange?.invoke(next)
        }
    }

    companion object {
        fun ticksPerValue(max: Int): Int = if (max <= 25) 4 else 1

        /** Dragging right pulls lower values under the marker, as a ruler does. */
        fun valueAfterDrag(start: Float, dx: Float, pxPerValue: Float, max: Int): Float =
            (start - dx / pxPerValue).coerceIn(0f, max.toFloat())
    }
}
