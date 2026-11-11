package com.motorola.pixelcamera.computational

import android.graphics.Bitmap
import android.graphics.Color
import com.motorola.pixelcamera.hardware.DimensityOptimizer
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * High-Fidelity Computational HDR+ & Detail Reconstruction Engine.
 *
 * Implements professional computational photography pipeline:
 * 1. Multi-Frame Burst Fusion (Mertens-style exposure & SNR stacking)
 * 2. Google AWB (Color Constancy & Illuminant Neutralization)
 * 3. Local Dynamic Range Tone Mapping (Shadow Lifting & Specular Rolloff)
 * 4. Chroma Bilateral De-noising (eradicates color blotches in low-light shadows)
 * 5. Noise-Cored Luminance Sharpening with Anti-Halo Limiting
 */
object HdrProcessor {

    private const val CORING_THRESHOLD = 2.5f
    private const val TEXT_BOOST_STRENGTH = 1.15f
    private const val HALO_LIMIT = 38f

    var saturationMultiplier: Float = 1.0f
    var microContrastMultiplier: Float = 1.0f
    var sharpnessMultiplier: Float = 1.0f

    /**
     * Process single or multi-frame burst with Google HDR+ algorithm
     */
    suspend fun processHdr(
        frames: List<Bitmap>,
        enableGoogleAwb: Boolean = true
    ): Bitmap = withContext(DimensityOptimizer.computationDispatcher) {
        if (frames.isEmpty()) throw IllegalArgumentException("Frame list is empty")

        val base = frames[0]
        val width = base.width
        val height = base.height
        val totalPixels = width * height

        // Step 1: Multi-frame Exposure & SNR Fusion if multiple frames provided
        val srcPixels = IntArray(totalPixels)
        if (frames.size > 1) {
            fuseBurstFrames(frames, width, height, srcPixels)
        } else {
            base.getPixels(srcPixels, 0, width, 0, 0, width, height)
        }

        // Step 2: Google AWB (Auto White Balance) Color Constancy Transform
        if (enableGoogleAwb) {
            applyGoogleAwb(srcPixels, totalPixels)
        }

        // Step 3: Convert RGB to floating-point YCbCr color space
        val yPlane = FloatArray(totalPixels)
        val cbPlane = FloatArray(totalPixels)
        val crPlane = FloatArray(totalPixels)

        for (i in 0 until totalPixels) {
            val c = srcPixels[i]
            val r = Color.red(c).toFloat()
            val g = Color.green(c).toFloat()
            val b = Color.blue(c).toFloat()

            yPlane[i] = 0.299f * r + 0.587f * g + 0.114f * b
            cbPlane[i] = 128f - 0.168736f * r - 0.331264f * g + 0.5f * b
            crPlane[i] = 128f + 0.5f * r - 0.418688f * g - 0.081312f * b
        }

        // Step 4: Edge-Preserving Chroma De-noising
        val cleanCb = filterChroma(cbPlane, width, height)
        val cleanCr = filterChroma(crPlane, width, height)

        // Step 5: 8-Neighbor Omnidirectional Noise-Cored Luminance Sharpening with Anti-Halo Control
        val sharpY = FloatArray(totalPixels)
        val diagWeight = 0.7071f
        val normWeight = 1.0f / (4.0f + 4.0f * diagWeight)

        for (y in 1 until height - 1) {
            val rowOffset = y * width
            val northRow = (y - 1) * width
            val southRow = (y + 1) * width

            for (x in 1 until width - 1) {
                val idx = rowOffset + x

                val center = yPlane[idx]
                val north = yPlane[northRow + x]
                val south = yPlane[southRow + x]
                val west = yPlane[rowOffset + x - 1]
                val east = yPlane[rowOffset + x + 1]

                val nw = yPlane[northRow + x - 1]
                val ne = yPlane[northRow + x + 1]
                val sw = yPlane[southRow + x - 1]
                val se = yPlane[southRow + x + 1]

                val localMean = ((north + south + west + east) * 1.0f + (nw + ne + sw + se) * diagWeight) * normWeight
                val delta = center - localMean
                val absDelta = abs(delta)

                val boostedDelta = when {
                    absDelta < CORING_THRESHOLD -> 0f
                    absDelta <= 35.0f -> {
                        val sign = if (delta > 0) 1f else -1f
                        sign * (absDelta - CORING_THRESHOLD) * (TEXT_BOOST_STRENGTH * sharpnessMultiplier)
                    }
                    else -> {
                        val sign = if (delta > 0) 1f else -1f
                        val baseBoost = (35.0f - CORING_THRESHOLD) * (TEXT_BOOST_STRENGTH * sharpnessMultiplier)
                        val extra = (absDelta - 35.0f) * 0.25f * sharpnessMultiplier
                        sign * min(baseBoost + extra, HALO_LIMIT)
                    }
                }

                sharpY[idx] = (center + boostedDelta).coerceIn(0f, 255f)
            }
        }

        // Border preservation
        for (x in 0 until width) {
            sharpY[x] = yPlane[x]
            sharpY[(height - 1) * width + x] = yPlane[(height - 1) * width + x]
        }
        for (y in 0 until height) {
            sharpY[y * width] = yPlane[y * width]
            sharpY[y * width + width - 1] = yPlane[y * width + width - 1]
        }

        // Step 6: Reconstruct RGB with Pixel Local Dynamic Range Tone Mapping
        val outPixels = IntArray(totalPixels)
        for (i in 0 until totalPixels) {
            var yVal = sharpY[i]
            val cbVal = (cleanCb[i] - 128f) * saturationMultiplier
            val crVal = (cleanCr[i] - 128f) * saturationMultiplier

            // Shadow Lifting & Dynamic Highlight Compression (Google Tomte-style Tone Curve)
            yVal = applyPixelToneCurve(yVal, microContrastMultiplier)

            val r = (yVal + 1.402f * crVal).coerceIn(0f, 255f)
            val g = (yVal - 0.344136f * cbVal - 0.714136f * crVal).coerceIn(0f, 255f)
            val b = (yVal + 1.772f * cbVal).coerceIn(0f, 255f)

            outPixels[i] = Color.rgb(r.toInt(), g.toInt(), b.toInt())
        }

        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        output.setPixels(outPixels, 0, width, 0, 0, width, height)
        return@withContext output
    }

    /** Aligned noise-reducing merge of a burst, with no tone or sharpening changes. */
    suspend fun mergeBurst(frames: List<Bitmap>): Bitmap = withContext(DimensityOptimizer.computationDispatcher) {
        require(frames.isNotEmpty()) { "Frame list is empty" }
        if (frames.size == 1) return@withContext frames[0]
        val width = frames[0].width
        val height = frames[0].height
        val t0 = android.os.SystemClock.elapsedRealtime()
        val merged = mergeAligned(frames)
        android.util.Log.i("HdrProcessor", "mergeBurst ${frames.size} frames: ${android.os.SystemClock.elapsedRealtime() - t0}ms")
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            it.setPixels(merged, 0, width, 0, 0, width, height)
        }
    }

    /**
     * Multi-frame merge. Handheld burst frames are shifted against each other, so they are
     * aligned to the sharpest frame and merged with ghost rejection (same path as Night Sight).
     * Frames whose size differs from the first are dropped.
     */
    internal fun mergeAligned(frames: List<Bitmap>): IntArray {
        val width = frames[0].width
        val height = frames[0].height
        val usable = frames.filter { it.width == width && it.height == height }
        val pixels = arrayOfNulls<IntArray>(usable.size)
        val sharpness = DoubleArray(usable.size)
        java.util.stream.IntStream.range(0, usable.size).parallel().forEach { i ->
            val px = IntArray(width * height).also { usable[i].getPixels(it, 0, width, 0, 0, width, height) }
            pixels[i] = px
            sharpness[i] = BurstAligner.sharpness(px, width, height)
        }
        val refIndex = sharpness.indices.maxByOrNull { sharpness[it] } ?: 0
        return BurstAligner.alignAndMerge(usable.size, width, height, refIndex) { pixels[it]!! }
    }

    private fun fuseBurstFrames(frames: List<Bitmap>, width: Int, height: Int, outPixels: IntArray) {
        System.arraycopy(mergeAligned(frames), 0, outPixels, 0, width * height)
    }

    /**
     * Google AWB: Color Constancy & Gray-World Illuminant Compensation
     * Removes warm yellow/orange casts and produces signature Pixel neutral tones
     */
    private fun applyGoogleAwb(pixels: IntArray, count: Int) {
        var sumR = 0.0
        var sumG = 0.0
        var sumB = 0.0
        var validCount = 0

        // Sub-sample pixels for rapid 60fps calculation
        val step = max(1, count / 50000)
        for (i in 0 until count step step) {
            val c = pixels[i]
            val r = Color.red(c)
            val g = Color.green(c)
            val b = Color.blue(c)

            // Exclude completely blown-out or pitch-black pixels from illuminant estimate
            if (r in 15..240 && g in 15..240 && b in 15..240) {
                sumR += r
                sumG += g
                sumB += b
                validCount++
            }
        }

        if (validCount < 100) return

        val avgR = sumR / validCount
        val avgG = sumG / validCount
        val avgB = sumB / validCount

        // Gray-world scale factors with subtle Pixel cool/neutral tuning
        val scaleR = (avgG / avgR) * 0.98
        val scaleB = (avgG / avgB) * 1.03

        // Only apply if there is an actual noticeable color cast (prevents overcorrection)
        if (abs(scaleR - 1.0) < 0.04 && abs(scaleB - 1.0) < 0.04) return

        for (i in 0 until count) {
            val c = pixels[i]
            val r = (Color.red(c) * scaleR).toInt().coerceIn(0, 255)
            val g = Color.green(c)
            val b = (Color.blue(c) * scaleB).toInt().coerceIn(0, 255)
            pixels[i] = Color.rgb(r, g, b)
        }
    }

    /**
     * 3x3 Chroma Box filter to kill color noise while keeping luminosity razor sharp
     */
    private fun filterChroma(plane: FloatArray, width: Int, height: Int): FloatArray {
        val out = FloatArray(width * height)
        for (y in 1 until height - 1) {
            val row = y * width
            for (x in 1 until width - 1) {
                val idx = row + x
                out[idx] = (
                    plane[idx - width - 1] + plane[idx - width] + plane[idx - width + 1] +
                    plane[idx - 1]         + plane[idx]         + plane[idx + 1] +
                    plane[idx + width - 1] + plane[idx + width] + plane[idx + width + 1]
                ) / 9.0f
            }
        }
        // Border preservation
        for (x in 0 until width) {
            out[x] = plane[x]
            out[(height - 1) * width + x] = plane[(height - 1) * width + x]
        }
        for (y in 0 until height) {
            out[y * width] = plane[y * width]
            out[y * width + width - 1] = plane[y * width + width - 1]
        }
        return out
    }

    /**
     * Google Pixel Local Dynamic Range Tone Curve:
     * Lifts shadow details without grain amplification and gracefully rolls off highlights.
     */
    private fun applyPixelToneCurve(y: Float, contrastMultiplier: Float = 1.0f): Float {
        val norm = y / 255.0f

        // Shadow lifting formula: gently illuminates dark tones
        val shadowLift = 0.16f * norm * (1.0f - norm) * (1.0f - norm)

        // Smooth Hermite S-curve for contrast
        val hermite = norm * norm * (3.0f - 2.0f * norm)
        val contrastWeight = (0.60f * contrastMultiplier).coerceIn(0.2f, 0.95f)
        val blended = norm * (1.0f - contrastWeight) + hermite * contrastWeight + shadowLift

        return (blended * 255.0f).coerceIn(0f, 255f)
    }

    /**
     * Handheld Long Exposure / Motion Blur Accumulator:
     * Blends consecutive burst frames using dynamic temporal exposure accumulation,
     * creating silky water motion blur and vibrant light trails while preserving static details.
     */
    suspend fun blendLongExposure(frames: List<Bitmap>): Bitmap = withContext(DimensityOptimizer.computationDispatcher) {
        if (frames.isEmpty()) throw IllegalArgumentException("Frame list empty")
        if (frames.size == 1) return@withContext frames[0]

        val width = frames[0].width
        val height = frames[0].height
        val total = width * height
        val n = frames.size

        val accR = FloatArray(total)
        val accG = FloatArray(total)
        val accB = FloatArray(total)
        val maxLum = FloatArray(total)

        val temp = IntArray(total)

        for (frame in frames) {
            frame.getPixels(temp, 0, width, 0, 0, width, height)
            val weight = 1.0f / n
            for (i in 0 until total) {
                val c = temp[i]
                val r = Color.red(c).toFloat()
                val g = Color.green(c).toFloat()
                val b = Color.blue(c).toFloat()
                val lum = 0.299f * r + 0.587f * g + 0.114f * b

                accR[i] += r * weight
                accG[i] += g * weight
                accB[i] += b * weight

                if (lum > maxLum[i]) {
                    maxLum[i] = lum
                }
            }
        }

        val outPixels = IntArray(total)
        for (i in 0 until total) {
            var r = accR[i]
            var g = accG[i]
            var b = accB[i]

            // Highlight recovery for light trails: blend in maximum light if it's a moving streak
            val avgLum = 0.299f * r + 0.587f * g + 0.114f * b
            if (maxLum[i] > avgLum + 25f) {
                val streakBlend = 0.40f
                r = r * (1f - streakBlend) + maxLum[i] * streakBlend
                g = g * (1f - streakBlend) + maxLum[i] * streakBlend
                b = b * (1f - streakBlend) + maxLum[i] * streakBlend
            }

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
}
