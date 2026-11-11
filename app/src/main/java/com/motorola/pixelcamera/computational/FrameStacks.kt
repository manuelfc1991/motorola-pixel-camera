package com.motorola.pixelcamera.computational

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Merges that take one frame at a time, so a burst never has to sit in memory whole: twelve
 * 12 MP frames are 576 MB, more than the app may use. Each keeps running sums (6 bytes a pixel)
 * and one reused pixel buffer; the caller frees each frame as soon as it has been added.
 */
abstract class FrameStack {
    protected var width = 0
    protected var height = 0
    var count = 0
        private set
    protected lateinit var sumR: ShortArray
    protected lateinit var sumG: ShortArray
    protected lateinit var sumB: ShortArray
    private var scratch: IntArray? = null

    /** Adds a frame; frames of another size than the first are skipped. */
    fun add(bitmap: Bitmap) {
        val total = bitmap.width * bitmap.height
        val px = scratch?.takeIf { it.size == total } ?: IntArray(total).also { scratch = it }
        bitmap.getPixels(px, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        add(px, bitmap.width, bitmap.height)
    }

    fun add(px: IntArray, w: Int, h: Int) {
        if (count == 0) {
            width = w; height = h
            sumR = ShortArray(w * h); sumG = ShortArray(w * h); sumB = ShortArray(w * h)
            start(px)
        } else if (w != width || h != height || count >= MAX_FRAMES) {
            return
        }
        accumulate(px)
        count++
    }

    protected open fun start(px: IntArray) {}
    protected abstract fun accumulate(px: IntArray)
    protected abstract fun finish(out: IntArray)

    /** The merged picture, or null if nothing was added. */
    fun result(): Bitmap? {
        if (count == 0) return null
        scratch = null
        val out = IntArray(width * height)
        finish(out)
        return Bitmap.createBitmap(out, width, height, Bitmap.Config.ARGB_8888)
    }

    /** The merged pixels (tests). */
    fun resultPixels(): IntArray? {
        if (count == 0) return null
        return IntArray(width * height).also { finish(it) }
    }

    protected fun addPixel(i: Int, p: Int) {
        sumR[i] = (sumR[i] + ((p shr 16) and 0xFF)).toShort()
        sumG[i] = (sumG[i] + ((p shr 8) and 0xFF)).toShort()
        sumB[i] = (sumB[i] + (p and 0xFF)).toShort()
    }

    companion object {
        /** Sums of 255 per frame stay inside a Short up to here. */
        const val MAX_FRAMES = 128

        fun rgb(r: Float, g: Float, b: Float): Int =
            (0xFF shl 24) or (r.toInt().coerceIn(0, 255) shl 16) or (g.toInt().coerceIn(0, 255) shl 8) or b.toInt().coerceIn(0, 255)

        fun luma(p: Int): Int = (((p shr 16) and 0xFF) * 77 + ((p shr 8) and 0xFF) * 150 + (p and 0xFF) * 29) shr 8
    }
}

/** Long exposure: the average of the frames, with moving lights pulled up into bright trails. */
class LongExposureStack : FrameStack() {
    private lateinit var maxLum: ByteArray

    override fun start(px: IntArray) {
        maxLum = ByteArray(px.size)
    }

    override fun accumulate(px: IntArray) {
        for (i in px.indices) {
            val p = px[i]
            addPixel(i, p)
            val l = luma(p)
            if (l > (maxLum[i].toInt() and 0xFF)) maxLum[i] = l.toByte()
        }
    }

    override fun finish(out: IntArray) {
        val n = count.toFloat()
        for (i in out.indices) {
            var r = sumR[i] / n
            var g = sumG[i] / n
            var b = sumB[i] / n
            val max = (maxLum[i].toInt() and 0xFF).toFloat()
            val avg = 0.299f * r + 0.587f * g + 0.114f * b
            if (max > avg + 25f) {
                r = r * (1f - STREAK) + max * STREAK
                g = g * (1f - STREAK) + max * STREAK
                b = b * (1f - STREAK) + max * STREAK
            }
            out[i] = rgb(r, g, b)
        }
    }

    private companion object {
        const val STREAK = 0.40f
    }
}

/**
 * Astrophotography: each frame is shifted so its stars land on the first frame's, then averaged,
 * the sky glow darkened and the stars lifted.
 */
class AstroStack : FrameStack() {
    data class Star(val x: Float, val y: Float)

    private var refStars: List<Star> = emptyList()

    override fun start(px: IntArray) {
        refStars = detectStars(px, width, height)
    }

    override fun accumulate(px: IntArray) {
        var dx = 0
        var dy = 0
        if (count > 0) {
            val (fx, fy) = drift(refStars, detectStars(px, width, height))
            if (abs(fx) < MAX_DRIFT && abs(fy) < MAX_DRIFT) { dx = fx.roundToInt(); dy = fy.roundToInt() }
        }
        if (dx == 0 && dy == 0) {
            for (i in px.indices) addPixel(i, px[i])
            return
        }
        for (y in 0 until height) {
            val srcRow = (y - dy).coerceIn(0, height - 1) * width
            val dstRow = y * width
            for (x in 0 until width) addPixel(dstRow + x, px[srcRow + (x - dx).coerceIn(0, width - 1)])
        }
    }

    override fun finish(out: IntArray) {
        val n = count.toFloat()
        for (i in out.indices) {
            var r = sumR[i] / n
            var g = sumG[i] / n
            var b = sumB[i] / n
            if (r * 0.299f + g * 0.587f + b * 0.114f < 45f) {
                // Darken light pollution and sky glow, keeping a little of the deep blue.
                r *= 0.88f; g *= 0.88f; b *= 0.95f
            } else {
                r *= 1.2f; g *= 1.2f; b *= 1.2f
            }
            out[i] = rgb(r, g, b)
        }
    }

    companion object {
        private const val GRID = 16
        private const val STAR_LUMA = 165
        private const val MAX_STARS = 40
        private const val MATCH_RADIUS = 35f
        private const val MAX_DRIFT = 40f

        fun detectStars(px: IntArray, w: Int, h: Int): List<Star> {
            val stars = ArrayList<Star>()
            for (y in GRID until h - GRID step GRID) for (x in GRID until w - GRID step GRID) {
                if (luma(px[y * w + x]) > STAR_LUMA) {
                    stars.add(Star(x.toFloat(), y.toFloat()))
                    if (stars.size >= MAX_STARS) return stars
                }
            }
            return stars
        }

        /** Average offset from each frame star to its nearest reference star. */
        fun drift(ref: List<Star>, cur: List<Star>): Pair<Float, Float> {
            var sx = 0f
            var sy = 0f
            var matched = 0
            for (rs in ref) {
                var best: Star? = null
                var bestD = MATCH_RADIUS
                for (cs in cur) {
                    val d = sqrt((rs.x - cs.x) * (rs.x - cs.x) + (rs.y - cs.y) * (rs.y - cs.y))
                    if (d < bestD) { bestD = d; best = cs }
                }
                if (best != null) { sx += rs.x - best.x; sy += rs.y - best.y; matched++ }
            }
            return if (matched > 0) Pair(sx / matched, sy / matched) else Pair(0f, 0f)
        }
    }
}
