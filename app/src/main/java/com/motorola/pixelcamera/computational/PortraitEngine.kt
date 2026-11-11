package com.motorola.pixelcamera.computational

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.os.SystemClock
import android.util.Log
import com.motorola.pixelcamera.hardware.DimensityOptimizer
import kotlinx.coroutines.withContext
import java.util.stream.IntStream
import kotlin.math.sqrt

/**
 * Portrait blur.
 *
 * With the neural models installed (see [PortraitSegmenter]) the subject is cut out along its
 * real outline and the background blurs more the farther it is behind the subject.
 * Without them the old look remains: a sharp circle around the focus point.
 */
object PortraitEngine {

    private const val TAG = "PortraitEngine"

    /** Slider value (1..40) to blur radius in pixels of the 768 px working grid. */
    private const val RADIUS_PER_STEP = 0.6f

    /** Blur amount at which the blurred layer fully replaces the sharp photo. */
    private const val FULL_COVER_AT = 0.33f

    suspend fun applyPortraitBokeh(
        context: Context,
        input: Bitmap,
        focusX: Float = 0.5f,
        focusY: Float = 0.45f,
        blurIntensity: Float = 14f
    ): Bitmap = withContext(DimensityOptimizer.computationDispatcher) {
        if (blurIntensity.toInt() <= 1) return@withContext input
        val started = SystemClock.elapsedRealtime()
        val map = PortraitSegmenter.analyse(context, input)
        val analysed = SystemClock.elapsedRealtime()
        val result = map?.let { depthBokeh(input, it, focusX, focusY, blurIntensity) }
        Log.i(TAG, "models ${analysed - started} ms, blur ${SystemClock.elapsedRealtime() - analysed} ms")
        if (result != null) {
            Log.i(TAG, "AI portrait in ${SystemClock.elapsedRealtime() - started} ms")
            return@withContext result
        }
        radialBokeh(input, focusX, focusY, blurIntensity)
    }

    /** Subject-mask and depth driven blur. Returns null when there is nothing to anchor on. */
    private fun depthBokeh(
        input: Bitmap, map: PortraitSegmenter.SubjectMap, focusX: Float, focusY: Float, blurIntensity: Float
    ): Bitmap? {
        val began = SystemClock.elapsedRealtime()
        val w = map.width
        val h = map.height
        val size = w * h
        val depth = map.depth
        // A mask that covers almost nothing means no person or object stood out.
        val alpha = map.alpha
            ?.takeIf { PortraitMath.subjectShare(it) >= PortraitMath.MIN_SUBJECT_SHARE }
            ?.let { PortraitMath.isolateSubject(it, depth, w, h, focusX, focusY) }

        val subjectDepth = when {
            alpha != null && depth != null -> PortraitMath.subjectDepth(alpha, depth)
            // No subject found: keep whatever the user tapped on sharp, by depth alone.
            depth != null -> {
                val x = (focusX * w).toInt().coerceIn(0, w - 1)
                val y = (focusY * h).toInt().coerceIn(0, h - 1)
                depth[y * w + x]
            }
            alpha != null -> 0f
            else -> return null
        }
        val amount = PortraitMath.blurAmount(alpha, depth, subjectDepth, w, h)

        val mapped = SystemClock.elapsedRealtime()

        // Blurred colours carry no fine detail, so they are worked out on a half-size grid.
        val bw = w / 2
        val bh = h / 2
        val blurSize = bw * bh
        val small = Bitmap.createScaledBitmap(input, bw, bh, true)
        val pixels = IntArray(blurSize)
        small.getPixels(pixels, 0, bw, 0, 0, bw, bh)
        if (small !== input) small.recycle()

        val background = if (alpha == null) FloatArray(blurSize) { 1f }
        else PortraitMath.resizeBilinear(alpha, w, h, bw, bh).also { for (i in it.indices) it[i] = 1f - it[i] }
        val amountSmall = PortraitMath.resizeBilinear(amount, w, h, bw, bh)
        val radius = (blurIntensity * RADIUS_PER_STEP / 2f).toInt().coerceAtLeast(2)

        // Per colour channel: a mild and a strong blur, mixed by how far behind the pixel is.
        val colour = Array(3) { FloatArray(0) }
        IntStream.range(0, 3).parallel().forEach { c ->
            val shift = 16 - 8 * c
            val channel = FloatArray(blurSize) { ((pixels[it] shr shift) and 0xFF).toFloat() }
            val mild = PortraitMath.backgroundBlur(channel, background, bw, bh, (radius / 2).coerceAtLeast(1))
            val strong = PortraitMath.backgroundBlur(channel, background, bw, bh, radius)
            for (i in 0 until blurSize) {
                val mix = ((amountSmall[i] - FULL_COVER_AT) / (1f - FULL_COVER_AT)).coerceIn(0f, 1f)
                mild[i] += (strong[i] - mild[i]) * mix
            }
            colour[c] = PortraitMath.resizeBilinear(mild, bw, bh, w, h)
        }

        // Blurred layer whose own alpha says how much of the sharp photo it hides. The alpha
        // stays on the full grid so the subject's outline keeps its precision.
        val layerPixels = IntArray(size)
        for (i in 0 until size) {
            val cover = (amount[i] / FULL_COVER_AT).coerceAtMost(1f)
            val r = colour[0][i].toInt().coerceIn(0, 255)
            val g = colour[1][i].toInt().coerceIn(0, 255)
            val b = colour[2][i].toInt().coerceIn(0, 255)
            layerPixels[i] = ((cover * 255f).toInt() shl 24) or (r shl 16) or (g shl 8) or b
        }
        val layer = Bitmap.createBitmap(layerPixels, w, h, Bitmap.Config.ARGB_8888)
        val blurred = SystemClock.elapsedRealtime()

        val output = input.copy(Bitmap.Config.ARGB_8888, true)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        Canvas(output).drawBitmap(layer, null, Rect(0, 0, output.width, output.height), paint)
        layer.recycle()
        Log.i(TAG, "blur map ${mapped - began} ms, layers ${blurred - mapped} ms, " +
            "composite ${SystemClock.elapsedRealtime() - blurred} ms")
        return output
    }

    private fun radialBokeh(input: Bitmap, focusX: Float, focusY: Float, blurIntensity: Float): Bitmap {
        val width = input.width
        val height = input.height

        val radius = blurIntensity.toInt().coerceIn(1, 40)
        if (radius <= 1) return input

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

        return output
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
