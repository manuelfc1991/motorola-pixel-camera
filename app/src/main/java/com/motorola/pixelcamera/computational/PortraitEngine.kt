package com.motorola.pixelcamera.computational

import android.graphics.Bitmap
import android.graphics.Color
import com.motorola.pixelcamera.hardware.DimensityOptimizer
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/**
 * Computational Portrait Blur & Depth Simulation Engine.
 * Creates progressive optical bokeh based on subject distance and focal plane.
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

        val srcPixels = IntArray(width * height)
        input.getPixels(srcPixels, 0, width, 0, 0, width, height)

        val outPixels = IntArray(width * height)

        val centerX = width * focusX
        val centerY = height * focusY
        val maxRadius = sqrt((width * width + height * height).toFloat()) * 0.42f

        val radiusStep = blurIntensity.toInt().coerceAtLeast(1)

        // Separable variable blur implementation
        for (y in 0 until height) {
            val yDist = (y - centerY)
            for (x in 0 until width) {
                val xDist = (x - centerX)
                val dist = sqrt(xDist * xDist + yDist * yDist)

                // Depth ratio (0 at subject focus, 1 at deep background)
                val depthRatio = ((dist - maxRadius * 0.5f) / (maxRadius * 0.5f)).coerceIn(0f, 1f)

                if (depthRatio < 0.05f) {
                    // In-focus subject region
                    outPixels[y * width + x] = srcPixels[y * width + x]
                } else {
                    // Variable kernel blur
                    val currentKernel = (radiusStep * depthRatio).toInt().coerceIn(1, 16)
                    var rSum = 0
                    var gSum = 0
                    var bSum = 0
                    var count = 0

                    for (ky in -currentKernel..currentKernel step 2) {
                        val sampleY = (y + ky).coerceIn(0, height - 1)
                        val rowOffset = sampleY * width
                        for (kx in -currentKernel..currentKernel step 2) {
                            val sampleX = (x + kx).coerceIn(0, width - 1)
                            val px = srcPixels[rowOffset + sampleX]
                            rSum += Color.red(px)
                            gSum += Color.green(px)
                            bSum += Color.blue(px)
                            count++
                        }
                    }

                    outPixels[y * width + x] = Color.rgb(
                        (rSum / count),
                        (gSum / count),
                        (bSum / count)
                    )
                }
            }
        }

        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        result.setPixels(outPixels, 0, width, 0, 0, width, height)
        return@withContext result
    }
}
