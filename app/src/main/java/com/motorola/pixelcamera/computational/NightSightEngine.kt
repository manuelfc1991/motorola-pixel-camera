package com.motorola.pixelcamera.computational

import android.graphics.Bitmap
import android.graphics.Color
import com.motorola.pixelcamera.hardware.DimensityOptimizer
import kotlinx.coroutines.withContext

/**
 * Computational Night Sight Stacking Engine.
 * Aligned multi-frame merge for handheld noise reduction, then shadow brightening.
 */
object NightSightEngine {

    suspend fun processNightSight(frames: List<Bitmap>): Bitmap = withContext(DimensityOptimizer.computationDispatcher) {
        if (frames.isEmpty()) throw IllegalArgumentException("Frame list empty")
        if (frames.size == 1) return@withContext frames[0]

        val width = frames[0].width
        val height = frames[0].height

        // Frames can differ in size if the HAL changes output mid-burst; drop mismatches
        val usable = frames.filter { it.width == width && it.height == height }
        fun pixelsOf(bmp: Bitmap) = IntArray(width * height).also { bmp.getPixels(it, 0, width, 0, 0, width, height) }

        // Handheld frames are taken hundreds of ms apart, so they are shifted by tens of pixels.
        // Use the least-shaken frame as reference, align the rest to it, and merge with
        // ghost rejection. Averaging them unaligned is what caused double/blurred images.
        val refIndex = usable.indices.maxByOrNull { BurstAligner.sharpness(pixelsOf(usable[it]), width, height) } ?: 0
        val merged = BurstAligner.alignAndMerge(usable.size, width, height, refIndex) { pixelsOf(usable[it]) }

        // Gamma boost factor for low-light shadow enhancement
        val boost = 1.25f

        for (i in 0 until width * height) {
            val c = merged[i]
            var r = Color.red(c) * boost
            var g = Color.green(c) * boost
            var b = Color.blue(c) * boost

            // Non-linear shadow expansion (lifts dark areas without blowing out lamp lights)
            r = 255f * (r / 255f).coerceIn(0f, 1f).pow(0.85f)
            g = 255f * (g / 255f).coerceIn(0f, 1f).pow(0.85f)
            b = 255f * (b / 255f).coerceIn(0f, 1f).pow(0.85f)

            merged[i] = Color.rgb(
                DimensityOptimizer.clamp(r.toInt()),
                DimensityOptimizer.clamp(g.toInt()),
                DimensityOptimizer.clamp(b.toInt())
            )
        }

        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        output.setPixels(merged, 0, width, 0, 0, width, height)
        return@withContext output
    }

    private fun Float.pow(n: Float): Float = Math.pow(this.toDouble(), n.toDouble()).toFloat()
}
