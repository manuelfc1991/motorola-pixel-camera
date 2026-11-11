package com.motorola.pixelcamera.computational

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import com.motorola.pixelcamera.hardware.DimensityOptimizer
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/**
 * Computational Portrait Blur & Depth Simulation Engine.
 * Creates progressive optical bokeh based on subject distance and aperture slider.
 * Uses high-performance separable box blur with radial focal depth masking.
 */
object PortraitEngine {

    suspend fun applyPortraitBokeh(
        input: Bitmap,
        focusX: Float = 0.5f,
        focusY: Float = 0.45f,
        blurIntensity: Float = 14f
    ): Bitmap = withContext(DimensityOptimizer.computationDispatcher) {
        val width = input.width
        val height = input.height

        val radius = blurIntensity.toInt().coerceIn(1, 40)
        if (radius <= 1) return@withContext input

        // 1. Generate blurred background layer using fast downscaled separable blur
        val scale = 0.5f
        val smallW = (width * scale).toInt().coerceAtLeast(1)
        val smallH = (height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(input, smallW, smallH, true)

        val smallPixels = IntArray(smallW * smallH)
        scaled.getPixels(smallPixels, 0, smallW, 0, 0, smallW, smallH)
        val smallBlur = fastBoxBlur(smallPixels, smallW, smallH, (radius * scale).toInt().coerceAtLeast(1))
        val blurredSmallBitmap = Bitmap.createBitmap(smallW, smallH, Bitmap.Config.ARGB_8888)
        blurredSmallBitmap.setPixels(smallBlur, 0, smallW, 0, 0, smallW, smallH)

        val blurredLayer = Bitmap.createScaledBitmap(blurredSmallBitmap, width, height, true)

        // 2. Blend sharp foreground (center/subject region) with blurred background
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        // Draw blurred background
        canvas.drawBitmap(blurredLayer, 0f, 0f, null)

        // Draw sharp foreground clipped with smooth radial gradient
        val centerX = width * focusX
        val centerY = height * focusY
        val focusRadius = sqrt((width * width + height * height).toFloat()) * 0.22f
        val featherRadius = focusRadius * 1.8f

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                centerX, centerY, featherRadius,
                intArrayOf(Color.WHITE, Color.WHITE, Color.TRANSPARENT),
                floatArrayOf(0f, 0.42f, 1f),
                Shader.TileMode.CLAMP
            )
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }

        val sharpCopy = input.copy(Bitmap.Config.ARGB_8888, true)
        // JPEG-decoded bitmaps are opaque; without alpha the DST_IN mask turns the edges black.
        sharpCopy.setHasAlpha(true)
        val sharpCanvas = Canvas(sharpCopy)
        sharpCanvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

        // Overlay masked sharp subject
        val normalPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawBitmap(sharpCopy, 0f, 0f, normalPaint)

        return@withContext output
    }

    private fun fastBoxBlur(pixels: IntArray, w: Int, h: Int, radius: Int): IntArray {
        val out = IntArray(w * h)
        val temp = IntArray(w * h)
        val div = radius + radius + 1

        // Horizontal pass
        for (y in 0 until h) {
            val rowOffset = y * w
            var rSum = 0
            var gSum = 0
            var bSum = 0

            for (i in -radius..radius) {
                val px = pixels[rowOffset + i.coerceIn(0, w - 1)]
                rSum += (px shr 16) and 0xFF
                gSum += (px shr 8) and 0xFF
                bSum += px and 0xFF
            }

            for (x in 0 until w) {
                temp[rowOffset + x] = ((rSum / div) shl 16) or ((gSum / div) shl 8) or (bSum / div) or (0xFF shl 24)
                val removePx = pixels[rowOffset + (x - radius).coerceIn(0, w - 1)]
                val addPx = pixels[rowOffset + (x + radius + 1).coerceIn(0, w - 1)]
                rSum += ((addPx shr 16) and 0xFF) - ((removePx shr 16) and 0xFF)
                gSum += ((addPx shr 8) and 0xFF) - ((removePx shr 8) and 0xFF)
                bSum += (addPx and 0xFF) - (removePx and 0xFF)
            }
        }

        // Vertical pass
        for (x in 0 until w) {
            var rSum = 0
            var gSum = 0
            var bSum = 0

            for (i in -radius..radius) {
                val px = temp[i.coerceIn(0, h - 1) * w + x]
                rSum += (px shr 16) and 0xFF
                gSum += (px shr 8) and 0xFF
                bSum += px and 0xFF
            }

            for (y in 0 until h) {
                val idx = y * w + x
                out[idx] = ((rSum / div) shl 16) or ((gSum / div) shl 8) or (bSum / div) or (0xFF shl 24)
                val removePx = temp[(y - radius).coerceIn(0, h - 1) * w + x]
                val addPx = temp[(y + radius + 1).coerceIn(0, h - 1) * w + x]
                rSum += ((addPx shr 16) and 0xFF) - ((removePx shr 16) and 0xFF)
                gSum += ((addPx shr 8) and 0xFF) - ((removePx shr 8) and 0xFF)
                bSum += (addPx and 0xFF) - (removePx and 0xFF)
            }
        }
        return out
    }
}
