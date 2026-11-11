package com.motorola.pixelcamera.computational

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Pure array maths behind the portrait look: no Android types, so it runs in plain unit tests.
 * All images here are row-major float arrays on one small working grid.
 */
internal object PortraitMath {

    /** How far (in normalised depth) something may sit from the subject and stay fully sharp. */
    private const val DEPTH_SHARP_BAND = 0.06f

    /** Depth distance at which the background reaches full blur. */
    private const val DEPTH_FULL_BLUR = 0.40f

    /** Below this share of the frame the segmenter found nothing worth calling a subject. */
    const val MIN_SUBJECT_SHARE = 0.01f

    fun resizeBilinear(src: FloatArray, sw: Int, sh: Int, dw: Int, dh: Int): FloatArray {
        if (sw == dw && sh == dh) return src.copyOf()
        val out = FloatArray(dw * dh)
        val xScale = sw.toFloat() / dw
        val yScale = sh.toFloat() / dh
        for (y in 0 until dh) {
            val fy = ((y + 0.5f) * yScale - 0.5f).coerceIn(0f, sh - 1f)
            val y0 = fy.toInt()
            val y1 = min(y0 + 1, sh - 1)
            val wy = fy - y0
            for (x in 0 until dw) {
                val fx = ((x + 0.5f) * xScale - 0.5f).coerceIn(0f, sw - 1f)
                val x0 = fx.toInt()
                val x1 = min(x0 + 1, sw - 1)
                val wx = fx - x0
                val top = src[y0 * sw + x0] * (1 - wx) + src[y0 * sw + x1] * wx
                val bottom = src[y1 * sw + x0] * (1 - wx) + src[y1 * sw + x1] * wx
                out[y * dw + x] = top * (1 - wy) + bottom * wy
            }
        }
        return out
    }

    /** Swaps rows and columns: a w x h image becomes h x w. */
    fun transpose(src: FloatArray, w: Int, h: Int): FloatArray {
        val out = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) out[x * h + y] = src[y * w + x]
        return out
    }

    /** Stretches values to 0..1. A flat input becomes all zeros. */
    fun normalise(values: FloatArray): FloatArray {
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (v in values) {
            if (v < lo) lo = v
            if (v > hi) hi = v
        }
        val range = hi - lo
        if (range < 1e-6f) return FloatArray(values.size)
        return FloatArray(values.size) { (values[it] - lo) / range }
    }

    /** Share of the frame the subject mask covers, 0..1. */
    fun subjectShare(alpha: FloatArray): Float {
        var sum = 0.0
        for (a in alpha) sum += a
        return (sum / alpha.size).toFloat()
    }

    /** Mask-weighted mean depth: where the subject stands. */
    fun subjectDepth(alpha: FloatArray, depth: FloatArray): Float {
        var sum = 0.0
        var weight = 0.0
        for (i in alpha.indices) {
            val a = alpha[i]
            if (a > 0.5f) {
                sum += depth[i] * a
                weight += a
            }
        }
        return if (weight > 0.0) (sum / weight).toFloat() else 0.5f
    }

    /**
     * Blur amount per pixel, 0 (sharp) to 1 (full blur).
     * The subject mask always stays sharp. Outside it, blur grows with distance in depth from
     * the subject, so the floor at the subject's feet stays crisp and the far wall melts away.
     * Without a depth map everything outside the mask gets full blur.
     */
    fun blurAmount(alpha: FloatArray?, depth: FloatArray?, subjectDepth: Float): FloatArray {
        val size = alpha?.size ?: depth?.size ?: 0
        return FloatArray(size) { i ->
            val outside = 1f - (alpha?.get(i) ?: 0f)
            val byDepth = if (depth == null) 1f else {
                val distance = abs(depth[i] - subjectDepth)
                smoothStep(DEPTH_SHARP_BAND, DEPTH_FULL_BLUR, distance)
            }
            outside * byDepth
        }
    }

    fun smoothStep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /**
     * Blurs [channel] counting only background pixels (weight = 1 - alpha), so the subject's
     * colours never bleed into the blurred background as a halo around its outline.
     * Two box passes give a soft, lens-like falloff.
     */
    fun backgroundBlur(channel: FloatArray, weight: FloatArray, w: Int, h: Int, radius: Int): FloatArray {
        val weighted = FloatArray(channel.size) { channel[it] * weight[it] }
        val sum = boxBlur(boxBlur(weighted, w, h, radius), w, h, radius)
        val norm = boxBlur(boxBlur(weight, w, h, radius), w, h, radius)
        return FloatArray(channel.size) { if (norm[it] > 1e-3f) sum[it] / norm[it] else channel[it] }
    }

    /** Separable box blur with clamped edges. */
    fun boxBlur(src: FloatArray, w: Int, h: Int, radius: Int): FloatArray {
        if (radius < 1) return src.copyOf()
        val div = (2 * radius + 1).toFloat()
        val temp = FloatArray(src.size)
        for (y in 0 until h) {
            val row = y * w
            var acc = 0f
            for (i in -radius..radius) acc += src[row + i.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                temp[row + x] = acc / div
                acc += src[row + min(x + radius + 1, w - 1)] - src[row + max(x - radius, 0)]
            }
        }
        val out = FloatArray(src.size)
        for (x in 0 until w) {
            var acc = 0f
            for (i in -radius..radius) acc += temp[i.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                out[y * w + x] = acc / div
                acc += temp[min(y + radius + 1, h - 1) * w + x] - temp[max(y - radius, 0) * w + x]
            }
        }
        return out
    }
}
