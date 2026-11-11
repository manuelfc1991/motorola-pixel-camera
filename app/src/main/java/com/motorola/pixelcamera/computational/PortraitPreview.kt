package com.motorola.pixelcamera.computational

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
     * Colours are blurred at half the grid size (they carry no detail); the outline keeps the full grid.
     */
    fun overlay(pixels: IntArray, mask: FloatArray, w: Int, h: Int, blurIntensity: Float): IntArray {
        val sw = (w / 2).coerceAtLeast(1)
        val sh = (h / 2).coerceAtLeast(1)
        val small = sw * sh
        val weight = FloatArray(small)
        val r = FloatArray(small)
        val g = FloatArray(small)
        val b = FloatArray(small)
        for (y in 0 until sh) for (x in 0 until sw) {
            var bw = 0f; var rs = 0f; var gs = 0f; var bs = 0f
            for (dy in 0..1) for (dx in 0..1) {
                val i = ((y * 2 + dy).coerceAtMost(h - 1)) * w + (x * 2 + dx).coerceAtMost(w - 1)
                val bg = (1f - mask[i]).coerceIn(0f, 1f)
                val p = pixels[i]
                bw += bg
                rs += ((p shr 16) and 0xFF) * bg
                gs += ((p shr 8) and 0xFF) * bg
                bs += (p and 0xFF) * bg
            }
            val j = y * sw + x
            weight[j] = bw / 4f; r[j] = rs / 4f; g[j] = gs / 4f; b[j] = bs / 4f
        }
        // Blur weighted colour and weight alike, then divide: an average over background only.
        val radius = radiusFor(blurIntensity, sw)
        fun blur(a: FloatArray) = PortraitMath.boxBlur(PortraitMath.boxBlur(a, sw, sh, radius), sw, sh, radius)
        val norm = blur(weight)
        val channels = arrayOf(blur(r), blur(g), blur(b))
        for (c in channels) for (i in 0 until small) {
            c[i] = if (norm[i] > 1e-3f) c[i] / norm[i] else 0f
        }
        val full = channels.map { PortraitMath.resizeBilinear(it, sw, sh, w, h) }
        return IntArray(w * h) { i ->
            // Edge pixels of the mask are partly subject: they let that much of the live picture through.
            val cover = smoothstep((1f - mask[i]).coerceIn(0f, 1f))
            val a = (cover * 255f + 0.5f).toInt()
            (a shl 24) or
                (full[0][i].toInt().coerceIn(0, 255) shl 16) or
                (full[1][i].toInt().coerceIn(0, 255) shl 8) or
                full[2][i].toInt().coerceIn(0, 255)
        }
    }

    private fun smoothstep(t: Float): Float {
        val x = ((t - 0.2f) / 0.6f).coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    /**
     * Fills [out] ([outW] x [outH], ARGB) from a copy of an RGBA camera buffer, turned upright by
     * [rotation] degrees and limited to upright columns [cols]. Nearest pixel; no bitmaps made.
     */
    fun sampleUpright(
        rgba: ByteArray, rowStride: Int, srcW: Int, srcH: Int, rotation: Int,
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
                    ((rgba[i].toInt() and 0xFF) shl 16) or
                    ((rgba[i + 1].toInt() and 0xFF) shl 8) or
                    (rgba[i + 2].toInt() and 0xFF)
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
