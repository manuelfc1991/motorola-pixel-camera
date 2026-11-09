package com.motorola.pixelcamera.computational

import android.graphics.Bitmap
import android.graphics.Color
import com.motorola.pixelcamera.hardware.DimensityOptimizer
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/**
 * Computational Night Sight Stacking Engine.
 * Multi-frame averaging for temporal noise suppression and dynamic shadow brightening.
 */
object NightSightEngine {

    suspend fun processNightSight(frames: List<Bitmap>): Bitmap = withContext(DimensityOptimizer.computationDispatcher) {
        if (frames.isEmpty()) throw IllegalArgumentException("Frame list empty")
        if (frames.size == 1) return@withContext frames[0]

        val width = frames[0].width
        val height = frames[0].height
        val numFrames = frames.size

        val sumR = FloatArray(width * height)
        val sumG = FloatArray(width * height)
        val sumB = FloatArray(width * height)

        val tempPixels = IntArray(width * height)

        for (frame in frames) {
            frame.getPixels(tempPixels, 0, width, 0, 0, width, height)
            for (i in 0 until width * height) {
                val c = tempPixels[i]
                sumR[i] += Color.red(c).toFloat()
                sumG[i] += Color.green(c).toFloat()
                sumB[i] += Color.blue(c).toFloat()
            }
        }

        val outPixels = IntArray(width * height)
        val invN = 1.0f / numFrames

        // Gamma boost factor for low-light shadow enhancement
        val boost = 1.25f

        for (i in 0 until width * height) {
            var r = (sumR[i] * invN) * boost
            var g = (sumG[i] * invN) * boost
            var b = (sumB[i] * invN) * boost

            // Non-linear shadow expansion (lifts dark areas without blowing out lamp lights)
            r = 255f * (r / 255f).coerceIn(0f, 1f).pow(0.85f)
            g = 255f * (g / 255f).coerceIn(0f, 1f).pow(0.85f)
            b = 255f * (b / 255f).coerceIn(0f, 1f).pow(0.85f)

            outPixels[i] = Color.rgb(
                DimensityOptimizer.clamp(r.toInt()),
                DimensityOptimizer.clamp(g.toInt()),
                DimensityOptimizer.clamp(b.toInt())
            )
        }

        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        output.setPixels(outPixels, 0, width, 0, 0, width, height)
        return@withContext output
    }

    private fun Float.pow(n: Float): Float = Math.pow(this.toDouble(), n.toDouble()).toFloat()
}
