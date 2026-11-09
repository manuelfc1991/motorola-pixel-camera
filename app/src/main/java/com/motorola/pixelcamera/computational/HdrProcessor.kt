package com.motorola.pixelcamera.computational

import android.graphics.Bitmap
import android.graphics.Color
import com.motorola.pixelcamera.hardware.DimensityOptimizer
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Computational HDR+ Fusion Engine.
 * Implements multi-frame exposure weighting, ghost reduction, highlight recovery,
 * and high-frequency edge detail enhancement for the Samsung ISOCELL HM2 sensor.
 */
object HdrProcessor {

    suspend fun processHdr(frames: List<Bitmap>): Bitmap = withContext(DimensityOptimizer.computationDispatcher) {
        if (frames.isEmpty()) throw IllegalArgumentException("Frame list is empty")
        if (frames.size == 1) return@withContext frames[0]

        val refFrame = frames[0]
        val width = refFrame.width
        val height = refFrame.height

        val outPixels = IntArray(width * height)
        val refPixels = IntArray(width * height)
        refFrame.getPixels(refPixels, 0, width, 0, 0, width, height)

        val altPixelsList = ArrayList<IntArray>()
        for (i in 1 until frames.size) {
            val f = frames[i]
            val px = IntArray(width * height)
            f.getPixels(px, 0, width, 0, 0, width, height)
            altPixelsList.add(px)
        }

        val totalFrames = 1 + altPixelsList.size

        // Multi-frame luminance-weighted accumulation
        for (idx in 0 until width * height) {
            var sumR = 0f
            var sumG = 0f
            var sumB = 0f
            var totalWeight = 0f

            // Process reference frame
            val refColor = refPixels[idx]
            val refR = Color.red(refColor)
            val refG = Color.green(refColor)
            val refB = Color.blue(refColor)
            val refWeight = computeExposureWeight(refR, refG, refB)

            sumR += refR * refWeight
            sumG += refG * refWeight
            sumB += refB * refWeight
            totalWeight += refWeight

            // Process alternate frames with motion de-ghosting check
            for (p in altPixelsList) {
                val color = p[idx]
                val r = Color.red(color)
                val g = Color.green(color)
                val b = Color.blue(color)

                // De-ghosting: if luminance deviation is too extreme, suppress weight
                val lumDiff = abs((refR * 0.299f + refG * 0.587f + refB * 0.114f) -
                                  (r * 0.299f + g * 0.587f + b * 0.114f))

                if (lumDiff < 140f) {
                    val w = computeExposureWeight(r, g, b)
                    sumR += r * w
                    sumG += g * w
                    sumB += b * w
                    totalWeight += w
                }
            }

            if (totalWeight < 0.001f) totalWeight = 1.0f

            var finalR = (sumR / totalWeight)
            var finalG = (sumG / totalWeight)
            var finalB = (sumB / totalWeight)

            // Dynamic range tone-mapping (Reinhard curve on shadows and highlights)
            finalR = toneMap(finalR)
            finalG = toneMap(finalG)
            finalB = toneMap(finalB)

            outPixels[idx] = Color.rgb(
                DimensityOptimizer.clamp(finalR.toInt()),
                DimensityOptimizer.clamp(finalG.toInt()),
                DimensityOptimizer.clamp(finalB.toInt())
            )
        }

        // Apply edge-detail sharpening (Unsharp Masking) to defeat stock camera smoothing
        applyDetailSharpening(outPixels, width, height)

        val resultBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        resultBitmap.setPixels(outPixels, 0, width, 0, 0, width, height)
        return@withContext resultBitmap
    }

    /**
     * Well-exposedness Gaussian weighting centered at 128 (optimal middle gray)
     */
    private fun computeExposureWeight(r: Int, g: Int, b: Int): Float {
        val lum = (r * 0.299f + g * 0.587f + b * 0.114f) / 255.0f
        val sigma = 0.22f
        return exp(-((lum - 0.5f).pow(2)) / (2f * sigma * sigma)) + 0.01f
    }

    private fun toneMap(value: Float): Float {
        val normalized = value / 255.0f
        // Sigmoid S-curve for contrast and shadow lifting
        val mapped = normalized / (1.0f + normalized * 0.35f) * 1.35f
        return min(255f, max(0f, mapped * 255f))
    }

    /**
     * High-pass 3x3 convolution kernel for fine texture restoration
     */
    private fun applyDetailSharpening(pixels: IntArray, width: Int, height: Int) {
        val copy = pixels.clone()
        val strength = 0.35f // Controlled Pixel-style sharpness without haloing

        for (y in 1 until height - 1) {
            val rowOffset = y * width
            for (x in 1 until width - 1) {
                val idx = rowOffset + x

                val center = copy[idx]
                val north = copy[idx - width]
                val south = copy[idx + width]
                val west = copy[idx - 1]
                val east = copy[idx + 1]

                val rCenter = Color.red(center)
                val gCenter = Color.green(center)
                val bCenter = Color.blue(center)

                val laplacianR = (4 * rCenter - Color.red(north) - Color.red(south) - Color.red(west) - Color.red(east))
                val laplacianG = (4 * gCenter - Color.green(north) - Color.green(south) - Color.green(west) - Color.green(east))
                val laplacianB = (4 * bCenter - Color.blue(north) - Color.blue(south) - Color.blue(west) - Color.blue(east))

                pixels[idx] = Color.rgb(
                    DimensityOptimizer.clamp((rCenter + laplacianR * strength).toInt()),
                    DimensityOptimizer.clamp((gCenter + laplacianG * strength).toInt()),
                    DimensityOptimizer.clamp((bCenter + laplacianB * strength).toInt())
                )
            }
        }
    }
}
