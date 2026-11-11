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
    private const val DEPTH_SHARP_BAND = 0.05f

    /** Depth distance at which the background reaches full blur. */
    private const val DEPTH_FULL_BLUR = 0.35f

    /** Things in front of the subject get this share of the blur things behind it get. */
    private const val FRONT_BLUR_SHARE = 0.5f

    /** Least blur for anything well away from the subject, whatever its depth says. */
    private const val MIN_BLUR_AWAY = 0.4f

    /** Mask pixels this much farther than the focused spot belong to something else. */
    private const val BEHIND_SUBJECT_CUT = 0.18f

    /** Grids at least this wide and tall get their blur map computed at half size. */
    private const val HALF_SIZE_FROM = 256

    /** The focused spot's depth is sampled within width / this around the seed pixel. */
    private const val SEED_REACH_DIVISOR = 6

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
     * Narrows a subject mask to the one object at the focus point.
     *
     * The segmenter marks everything that stands out, so a monitor or keyboard behind the real
     * subject would stay sharp too. Starting from the mask pixel nearest the focus point, this
     * keeps only what is connected to it and, when depth is known, not clearly behind it.
     */
    fun isolateSubject(
        alpha: FloatArray, depth: FloatArray?, w: Int, h: Int, focusX: Float, focusY: Float
    ): FloatArray {
        val solid = BooleanArray(alpha.size) { alpha[it] > 0.5f }
        val fx = (focusX * w).toInt().coerceIn(0, w - 1)
        val fy = (focusY * h).toInt().coerceIn(0, h - 1)
        val seed = nearest(solid, w, fx, fy)
        if (seed < 0) return alpha.copyOf()
        var keep = flood(solid, w, h, seed)

        if (depth != null) {
            // Depth of the subject where it was focused: median of its pixels around the seed.
            val reach = w / SEED_REACH_DIVISOR
            val sx = seed % w
            val sy = seed / w
            val samples = ArrayList<Float>()
            for (y in max(0, sy - reach)..min(h - 1, sy + reach)) {
                for (x in max(0, sx - reach)..min(w - 1, sx + reach)) {
                    if (keep[y * w + x]) samples.add(depth[y * w + x])
                }
            }
            samples.sort()
            val seedDepth = samples[samples.size / 2]
            val gated = BooleanArray(alpha.size) { keep[it] && depth[it] > seedDepth - BEHIND_SUBJECT_CUT }
            val gatedSeed = nearest(gated, w, fx, fy)
            if (gatedSeed >= 0) keep = flood(gated, w, h, gatedSeed)
        }

        // Soften the cut so a removed neighbour does not leave a hard step in the mask.
        val soft = boxBlur(boxBlur(FloatArray(alpha.size) { if (keep[it]) 1f else 0f }, w, h, 3), w, h, 3)
        return FloatArray(alpha.size) { alpha[it] * soft[it] }
    }

    /** Index of the set pixel closest to (fx, fy), or -1 when none is set. */
    private fun nearest(mask: BooleanArray, w: Int, fx: Int, fy: Int): Int {
        var best = -1
        var bestDistance = Long.MAX_VALUE
        for (i in mask.indices) {
            if (!mask[i]) continue
            val dx = (i % w - fx).toLong()
            val dy = (i / w - fy).toLong()
            val distance = dx * dx + dy * dy
            if (distance < bestDistance) {
                bestDistance = distance
                best = i
            }
        }
        return best
    }

    /** All set pixels 4-connected to [seed]. */
    private fun flood(mask: BooleanArray, w: Int, h: Int, seed: Int): BooleanArray {
        val keep = BooleanArray(mask.size)
        val stack = IntArray(mask.size)
        var top = 0
        stack[top++] = seed
        keep[seed] = true
        while (top > 0) {
            val i = stack[--top]
            val x = i % w
            val y = i / w
            if (x > 0 && mask[i - 1] && !keep[i - 1]) { keep[i - 1] = true; stack[top++] = i - 1 }
            if (x < w - 1 && mask[i + 1] && !keep[i + 1]) { keep[i + 1] = true; stack[top++] = i + 1 }
            if (y > 0 && mask[i - w] && !keep[i - w]) { keep[i - w] = true; stack[top++] = i - w }
            if (y < h - 1 && mask[i + w] && !keep[i + w]) { keep[i + w] = true; stack[top++] = i + w }
        }
        return keep
    }

    /**
     * Blur amount per pixel, 0 (sharp) to 1 (full blur).
     * The subject mask always stays sharp. Outside it:
     *  - blur grows with how far behind the subject something is, so the surface the subject
     *    stands on stays crisp and the far wall melts away;
     *  - things in front of the subject blur only half as much;
     *  - away from the subject there is always some blur, which covers objects whose depth the
     *    model misjudged;
     *  - the map is smoothed so depth noise cannot leave blotches.
     * Without a depth map everything outside the mask gets full blur.
     */
    fun blurAmount(alpha: FloatArray?, depth: FloatArray?, subjectDepth: Float, w: Int, h: Int): FloatArray {
        val size = w * h
        if (depth == null) return FloatArray(size) { 1f - (alpha?.get(it) ?: 0f) }

        // The map is smooth by construction, so large grids work it out at half size.
        val step = if (w >= HALF_SIZE_FROM && h >= HALF_SIZE_FROM) 2 else 1
        val mw = w / step
        val mh = h / step
        val smallAlpha = alpha?.let { resizeBilinear(it, w, h, mw, mh) }
        val smallDepth = resizeBilinear(depth, w, h, mw, mh)

        val nearSubject = smallAlpha?.let { boxBlur(boxBlur(it, mw, mh, mw / 8), mw, mh, mw / 8) }
        val raw = FloatArray(mw * mh) { i ->
            val behind = subjectDepth - smallDepth[i]
            val byDepth = if (behind >= 0f) smoothStep(DEPTH_SHARP_BAND, DEPTH_FULL_BLUR, behind)
            else FRONT_BLUR_SHARE * smoothStep(DEPTH_SHARP_BAND * 2f, DEPTH_FULL_BLUR * 1.5f, -behind)
            val floor = if (nearSubject == null) 0f else MIN_BLUR_AWAY * (1f - smoothStep(0f, 0.3f, nearSubject[i]))
            max(byDepth, floor)
        }
        val smooth = resizeBilinear(boxBlur(boxBlur(raw, mw, mh, mw / 64), mw, mh, mw / 64), mw, mh, w, h)
        return FloatArray(size) { (1f - (alpha?.get(it) ?: 0f)) * smooth[it] }
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
