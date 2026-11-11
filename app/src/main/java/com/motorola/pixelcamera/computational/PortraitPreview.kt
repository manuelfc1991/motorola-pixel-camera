package com.motorola.pixelcamera.computational

import java.nio.ByteBuffer

/**
 * The live Portrait viewfinder: a small layer laid over the camera preview that shows the
 * background blurred and leaves the subject see-through, so the subject stays live and sharp
 * underneath while the blur matches what the photo will get.
 */
object PortraitPreview {

    /**
     * Blur radius on a grid [gridWidth] pixels wide for a slider [blurIntensity], scaled from the
     * photo's own blur (0.3 px per step on its 384 px wide blur grid).
     */
    fun radiusFor(blurIntensity: Float, gridWidth: Int): Int =
        (blurIntensity * 0.3f / 384f * gridWidth + 0.5f).toInt().coerceAtLeast(1)

    /**
     * Builds the layer from a frame ([pixels], ARGB, [w] x [h]) and its subject [mask] (1 = subject)
     * on the same grid. Background colour is blurred from background pixels only, so the subject
     * does not bleed into it; the layer's alpha hides the live picture where there is background.
     */
    fun overlay(pixels: IntArray, mask: FloatArray, w: Int, h: Int, blurIntensity: Float): IntArray {
        val size = w * h
        val background = FloatArray(size) { (1f - mask[it]).coerceIn(0f, 1f) }
        val radius = radiusFor(blurIntensity, w)
        val channels = Array(3) { c ->
            val shift = 16 - 8 * c
            val channel = FloatArray(size) { ((pixels[it] shr shift) and 0xFF).toFloat() }
            PortraitMath.backgroundBlur(channel, background, w, h, radius)
        }
        return IntArray(size) { i ->
            // Edge pixels of the mask are partly subject: they let that much of the live picture through.
            val cover = smoothstep(background[i])
            val a = (cover * 255f + 0.5f).toInt()
            (a shl 24) or
                (channels[0][i].toInt().coerceIn(0, 255) shl 16) or
                (channels[1][i].toInt().coerceIn(0, 255) shl 8) or
                channels[2][i].toInt().coerceIn(0, 255)
        }
    }

    private fun smoothstep(t: Float): Float {
        val x = ((t - 0.2f) / 0.6f).coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    /**
     * Fills [out] ([outW] x [outH], ARGB) straight from an RGBA camera buffer, turned upright by
     * [rotation] degrees and limited to upright columns [cols]. Nearest pixel; no bitmaps made.
     */
    fun sampleUpright(
        rgba: ByteBuffer, rowStride: Int, srcW: Int, srcH: Int, rotation: Int,
        cols: IntRange, outW: Int, outH: Int, out: IntArray
    ) {
        val uprightH = if (rotation % 180 == 0) srcH else srcW
        val cropW = cols.last - cols.first + 1
        val xs = IntArray(outW) { cols.first + ((it + 0.5f) * cropW / outW).toInt() }
        for (oy in 0 until outH) {
            val uy = ((oy + 0.5f) * uprightH / outH).toInt()
            for (ox in 0 until outW) {
                val ux = xs[ox]
                val sx: Int
                val sy: Int
                when (rotation) {
                    90 -> { sx = uy; sy = srcH - 1 - ux }
                    180 -> { sx = srcW - 1 - ux; sy = srcH - 1 - uy }
                    270 -> { sx = srcW - 1 - uy; sy = ux }
                    else -> { sx = ux; sy = uy }
                }
                val i = sy * rowStride + sx * 4
                out[oy * outW + ox] = (0xFF shl 24) or
                    ((rgba.get(i).toInt() and 0xFF) shl 16) or
                    ((rgba.get(i + 1).toInt() and 0xFF) shl 8) or
                    (rgba.get(i + 2).toInt() and 0xFF)
            }
        }
    }

    /** Columns to keep when the preview shows a narrower picture than the [w] x [h] frame. */
    fun cropForAspect(w: Int, h: Int, viewAspect: Float): IntRange {
        val keep = (h * viewAspect).toInt().coerceAtMost(w)
        val start = (w - keep) / 2
        return start until start + keep
    }
}
