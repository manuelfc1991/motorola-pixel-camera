package com.motorola.pixelcamera.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/**
 * Real-time RGB and Luminance Histogram for Pro Mode.
 */
class HistogramView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val rBins = FloatArray(256)
    private val gBins = FloatArray(256)
    private val bBins = FloatArray(256)

    private val paintR = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 239, 83, 80)
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }

    private val paintG = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 102, 187, 106)
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }

    private val paintB = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 66, 165, 245)
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }

    private val bgPaint = Paint().apply {
        color = Color.argb(150, 20, 22, 25)
        style = Paint.Style.FILL
    }

    private val pathR = Path()
    private val pathG = Path()
    private val pathB = Path()

    fun updateHistogram(red: IntArray, green: IntArray, blue: IntArray) {
        var maxCount = 1
        for (i in 0 until 256) {
            if (red[i] > maxCount) maxCount = red[i]
            if (green[i] > maxCount) maxCount = green[i]
            if (blue[i] > maxCount) maxCount = blue[i]
        }

        val norm = 1.0f / maxCount
        for (i in 0 until 256) {
            rBins[i] = red[i] * norm
            gBins[i] = green[i] * norm
            bBins[i] = blue[i] * norm
        }
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 16f, 16f, bgPaint)

        val w = width.toFloat()
        val h = height.toFloat()
        val step = w / 255.0f

        pathR.reset()
        pathG.reset()
        pathB.reset()

        pathR.moveTo(0f, h - rBins[0] * h)
        pathG.moveTo(0f, h - gBins[0] * h)
        pathB.moveTo(0f, h - bBins[0] * h)

        for (i in 1 until 256) {
            val x = i * step
            pathR.lineTo(x, h - rBins[i] * (h - 4f) - 2f)
            pathG.lineTo(x, h - gBins[i] * (h - 4f) - 2f)
            pathB.lineTo(x, h - bBins[i] * (h - 4f) - 2f)
        }

        canvas.drawPath(pathR, paintR)
        canvas.drawPath(pathG, paintG)
        canvas.drawPath(pathB, paintB)
    }
}
