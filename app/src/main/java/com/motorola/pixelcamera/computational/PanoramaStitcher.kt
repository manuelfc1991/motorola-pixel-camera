package com.motorola.pixelcamera.computational

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.tan

/**
 * Builds a panorama out of viewfinder frames.
 *
 * Each frame is laid onto a cylinder, then lined up against the previous frame by matching the
 * picture itself; the gyro only says roughly where to look. Only the middle of each frame is kept,
 * between seams halfway to its neighbours, with a short feather across each seam. Pans to the left
 * and to the right both work, and up/down drift of the hand is followed.
 *
 * Plain Kotlin on ARGB arrays (alpha 0 = no picture) so it can be tested off the phone.
 */
class PanoramaStitcher(
    val frameW: Int,
    val frameH: Int,
    /** Focal length of the frames in pixels; 0 leaves frames flat (used by tests). */
    private val focalPx: Float,
    /** Expected picture movement per degree of turn. Refined from the matches as the sweep goes. */
    pixelsPerDegree: Float,
    maxSweepDegrees: Float = 180f,
) {
    class Image(val pixels: IntArray, val width: Int, val height: Int)

    /** Where a frame sits relative to the previous one, and how sure the match is. */
    data class Shift(val dx: Int, val dy: Int, val error: Float, val texture: Float, val gain: Float, val onEdge: Boolean)

    private val initialPpd = pixelsPerDegree
    var pixelsPerDegree = pixelsPerDegree
        private set

    // Cylinder map, per column: where to sample in the flat frame, and how much to stretch rows.
    private val colSrcX = FloatArray(frameW)
    private val colScale = FloatArray(frameW)

    init {
        val halfW = frameW / 2f
        for (x in 0 until frameW) {
            if (focalPx <= 0f) {
                colSrcX[x] = x.toFloat(); colScale[x] = 1f
            } else {
                val angle = (x + 0.5f - halfW) / focalPx
                colSrcX[x] = focalPx * tan(angle) + halfW - 0.5f
                colScale[x] = 1f / cos(angle)
            }
        }
    }

    private val vMargin = (frameH * V_MARGIN).toInt()
    private val canvasW = (frameW + pixelsPerDegree * maxSweepDegrees * CANVAS_SLACK).toInt().coerceAtMost(MAX_CANVAS_W)
    private val canvasH = frameH + 2 * vMargin
    private var canvas: IntArray? = null

    private var first: IntArray? = null
    private var firstX = 0
    private var last: IntArray? = null
    private var lastCoarse: FloatArray? = null
    private var lastFine: FloatArray? = null
    private var lastYaw = 0f
    private var lastX = 0
    private var lastY = vMargin
    private var lastGain = 1f
    private var minY = vMargin
    private var maxY = vMargin

    /** +1 when the pan goes right, -1 left, 0 until the first match says which. */
    var direction = 0
        private set
    var frameCount = 0
        private set
    /** True once the canvas has no room for another frame. */
    var isFull = false
        private set

    private val cw = frameW / COARSE
    private val ch = frameH / COARSE
    private val fw = frameW / FINE
    private val fh = frameH / FINE

    /** Whether a frame taken now at [yaw] (degrees, signed) would be used. Saves converting the rest. */
    fun wantsFrame(yaw: Float): Boolean {
        if (isFull) return false
        if (first == null) return true
        return abs(yaw - lastYaw) * pixelsPerDegree >= frameW * STEP
    }

    /** Adds a frame ([frameW] x [frameH] ARGB) seen at [yaw] degrees. Returns true if it was used. */
    fun add(frame: IntArray, yaw: Float): Boolean {
        require(frame.size == frameW * frameH)
        if (isFull) return false
        val cyl = project(frame)
        if (first == null) {
            first = cyl
            remember(cyl, yaw, 0, vMargin, 1f)
            frameCount = 1
            return true
        }
        val prevCoarse = lastCoarse!!
        val prevFine = lastFine!!
        val curCoarse = luma(cyl, COARSE)
        val curFine = luma(cyl, FINE)

        val guess = ((yaw - lastYaw) * pixelsPerDegree).coerceIn(-frameW * MAX_GUESS, frameW * MAX_GUESS)
        val coarse = estimateShift(
            prevCoarse, curCoarse, cw, ch,
            (guess / COARSE).roundToInt(), 0,
            (frameW * SEARCH_X / COARSE).roundToInt(), (frameH * SEARCH_Y / COARSE).roundToInt().coerceAtLeast(1),
        )
        val ratio = COARSE / FINE
        val fine = coarse?.let {
            estimateShift(prevFine, curFine, fw, fh, it.dx * ratio, it.dy * ratio, ratio + 1, ratio + 1)
        }
        val sure = fine != null && !coarse.onEdge && fine.texture >= MIN_TEXTURE && fine.error < fine.texture * MAX_ERROR_TO_TEXTURE
        var dx = if (sure) fine!!.dx * FINE else guess.roundToInt()
        var dy = if (sure) fine!!.dy * FINE else 0
        val gain = if (sure) fine!!.gain else 1f

        if (abs(dx) < frameW * MIN_MOVE) return false          // not turned far enough yet
        if (direction == 0) direction = dx.sign
        else if (dx.sign != direction) return false              // turned back: wait until the pan resumes
        if (sure && abs(yaw - lastYaw) > 2f) {
            val measured = abs(dx) / abs(yaw - lastYaw)
            pixelsPerDegree = (pixelsPerDegree * 0.7f + measured * 0.3f).coerceIn(initialPpd * 0.5f, initialPpd * 2f)
        }

        var target = canvas
        if (target == null) {
            target = IntArray(canvasW * canvasH)
            canvas = target
            firstX = if (direction > 0) 0 else canvasW - frameW
            lastX = firstX
            draw(target, first!!, firstX, vMargin, 0, frameW, 1f, 1f, 0)
        }
        val x = lastX + dx
        val y = (lastY + dy).coerceIn(0, 2 * vMargin)
        if (x < 0 || x + frameW > canvasW) {
            isFull = true
            return false
        }
        val frameGain = (lastGain * gain).coerceIn(MIN_GAIN, MAX_GAIN)
        // The seam sits halfway between the two frame centres.
        val seam = lastX + frameW / 2 + dx / 2
        val band = minOf(FEATHER, abs(dx))
        if (direction > 0) draw(target, cyl, x, y, seam - band / 2 - x, frameW, frameGain, band.toFloat(), +1)
        else draw(target, cyl, x, y, 0, seam + band / 2 - x, frameGain, band.toFloat(), -1)

        remember(cyl, yaw, x, y, frameGain, curCoarse, curFine)
        minY = minOf(minY, y); maxY = maxOf(maxY, y)
        frameCount++
        return true
    }

    private fun remember(cyl: IntArray, yaw: Float, x: Int, y: Int, gain: Float,
                         coarse: FloatArray = luma(cyl, COARSE), fine: FloatArray = luma(cyl, FINE)) {
        last = cyl; lastCoarse = coarse; lastFine = fine
        lastYaw = yaw; lastX = x; lastY = y; lastGain = gain
    }

    /**
     * Writes columns [from, to) of [src] at canvas ([x], [y]). Within [band] pixels of the side the
     * frame comes in from ([side] +1: left edge, -1: right edge), it fades in over what is there.
     */
    private fun draw(target: IntArray, src: IntArray, x: Int, y: Int, from: Int, to: Int, gain: Float, band: Float, side: Int) {
        val c0 = from.coerceIn(0, frameW)
        val c1 = to.coerceIn(0, frameW)
        val g = (gain * 256).toInt()
        for (c in c0 until c1) {
            val weight = when {
                side == 0 || band <= 0f -> 1f
                side > 0 -> ((c - c0 + 0.5f) / band).coerceIn(0f, 1f)
                else -> ((c1 - c - 0.5f) / band).coerceIn(0f, 1f)
            }
            val wn = (weight * 256).toInt()
            var di = y * canvasW + x + c
            var si = c
            for (r in 0 until frameH) {
                val p = src[si]
                if (p ushr 24 != 0) {
                    val red = minOf(255, (((p shr 16) and 0xFF) * g) shr 8)
                    val green = minOf(255, (((p shr 8) and 0xFF) * g) shr 8)
                    val blue = minOf(255, ((p and 0xFF) * g) shr 8)
                    val old = target[di]
                    target[di] = if (wn >= 256 || old ushr 24 == 0) {
                        (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
                    } else {
                        val wo = 256 - wn
                        (0xFF shl 24) or
                            ((((old shr 16) and 0xFF) * wo + red * wn) shr 8 shl 16) or
                            ((((old shr 8) and 0xFF) * wo + green * wn) shr 8 shl 8) or
                            (((old and 0xFF) * wo + blue * wn) shr 8)
                    }
                }
                di += canvasW
                si += frameW
            }
        }
    }

    /** The stitched picture, trimmed to where every frame has picture. Null until two frames are in. */
    fun result(): Image? {
        val target = canvas ?: return null
        if (frameCount < 2) return null
        val left: Int
        val right: Int
        if (direction > 0) { left = firstX; right = lastX + frameW } else { left = lastX; right = firstX + frameW }
        // The ends of the first and last frame bend most on the cylinder; trim a little off both.
        val trimX = if (focalPx > 0f) (frameW * END_TRIM).toInt() else 0
        val inset = if (focalPx > 0f) {
            (frameH / 2f * (1f - cos((frameW / 2f - trimX) / focalPx))).toInt() + 1
        } else 0
        val x0 = left + trimX
        val x1 = right - trimX
        val y0 = maxY + inset
        val y1 = minY + frameH - inset
        val w = x1 - x0
        val h = y1 - y0
        if (w <= 0 || h <= 0) return null
        val out = IntArray(w * h)
        for (r in 0 until h) {
            System.arraycopy(target, (y0 + r) * canvasW + x0, out, r * w, w)
        }
        for (i in out.indices) if (out[i] ushr 24 == 0) out[i] = 0xFF000000.toInt()
        return Image(out, w, h)
    }

    /** A small copy of what has been stitched so far, [height] pixels tall, for the on-screen strip. */
    fun preview(height: Int): Image? {
        val target = canvas
        val src: IntArray
        val srcW: Int
        val left: Int
        val right: Int
        val top: Int
        if (target == null) {
            src = first ?: return null; srcW = frameW; left = 0; right = frameW; top = 0
        } else {
            src = target; srcW = canvasW; top = vMargin
            if (direction > 0) { left = firstX; right = lastX + frameW } else { left = lastX; right = firstX + frameW }
        }
        val scale = frameH.toFloat() / height
        val w = ((right - left) / scale).toInt().coerceAtLeast(1)
        val out = IntArray(w * height)
        for (r in 0 until height) {
            val sy = top + (r * scale).toInt()
            for (c in 0 until w) {
                val p = src[sy * srcW + left + (c * scale).toInt()]
                out[r * w + c] = if (p ushr 24 == 0) 0xFF000000.toInt() else p
            }
        }
        return Image(out, w, height)
    }

    /** Lays a flat frame onto the cylinder, sampling between pixels. Outside the frame stays alpha 0. */
    internal fun project(src: IntArray): IntArray {
        if (focalPx <= 0f) return src.copyOf()
        val out = IntArray(frameW * frameH)
        val halfH = frameH / 2f
        for (x in 0 until frameW) {
            val sx = colSrcX[x]
            if (sx < 0f || sx > frameW - 1f) continue
            val x0 = sx.toInt().coerceAtMost(frameW - 2)
            val fx = sx - x0
            val scale = colScale[x]
            for (y in 0 until frameH) {
                val sy = (y + 0.5f - halfH) * scale + halfH - 0.5f
                if (sy < 0f || sy > frameH - 1f) continue
                val y0 = sy.toInt().coerceAtMost(frameH - 2)
                val fy = sy - y0
                val i = y0 * frameW + x0
                out[y * frameW + x] = bilinear(src[i], src[i + 1], src[i + frameW], src[i + frameW + 1], fx, fy)
            }
        }
        return out
    }

    private fun bilinear(a: Int, b: Int, c: Int, d: Int, fx: Float, fy: Float): Int {
        val wa = (1 - fx) * (1 - fy); val wb = fx * (1 - fy); val wc = (1 - fx) * fy; val wd = fx * fy
        fun ch(s: Int) = (((a shr s) and 0xFF) * wa + ((b shr s) and 0xFF) * wb +
            ((c shr s) and 0xFF) * wc + ((d shr s) and 0xFF) * wd + 0.5f).toInt()
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** Brightness at 1/[factor] size; -1 where any pixel of the block has no picture. */
    internal fun luma(frame: IntArray, factor: Int): FloatArray {
        val w = frameW / factor
        val h = frameH / factor
        val out = FloatArray(w * h)
        for (by in 0 until h) for (bx in 0 until w) {
            var sum = 0
            var hole = false
            for (y in by * factor until by * factor + factor) {
                var i = y * frameW + bx * factor
                repeat(factor) {
                    val p = frame[i++]
                    if (p ushr 24 == 0) hole = true
                    sum += (((p shr 16) and 0xFF) * 77 + ((p shr 8) and 0xFF) * 150 + (p and 0xFF) * 29) shr 8
                }
            }
            out[by * w + bx] = if (hole) -1f else sum.toFloat() / (factor * factor)
        }
        return out
    }

    companion object {
        private const val COARSE = 8
        private const val FINE = 2
        /** A new frame is taken once the picture should have moved this share of a frame. */
        const val STEP = 0.25f
        private const val MIN_MOVE = 0.08f
        private const val MAX_GUESS = 0.6f
        private const val SEARCH_X = 0.2f
        private const val SEARCH_Y = 0.06f
        private const val V_MARGIN = 0.08f
        private const val CANVAS_SLACK = 1.4f
        private const val MAX_CANVAS_W = 12000
        private const val FEATHER = 64
        private const val END_TRIM = 0.1f
        private const val MIN_GAIN = 0.67f
        private const val MAX_GAIN = 1.5f
        /** Below this much brightness variation (0..255) a match is a guess: plain sky, a wall. */
        private const val MIN_TEXTURE = 2.5f
        private const val MAX_ERROR_TO_TEXTURE = 0.8f
        /** At least this share of the frame must overlap for a shift to be scored. */
        private const val MIN_OVERLAP = 0.25f

        /**
         * Finds where [cur] sits relative to [prev] (both [w] x [h] brightness, -1 = no picture):
         * cur(x, y) shows what prev(x + dx, y + dy) shows. Searches [cx] ± [rx], [cy] ± [ry].
         * Brightness is compared after removing each side's average, so exposure drift does not
         * pull the match. Null if no shift leaves enough overlap.
         */
        fun estimateShift(prev: FloatArray, cur: FloatArray, w: Int, h: Int, cx: Int, cy: Int, rx: Int, ry: Int): Shift? {
            var best: Shift? = null
            val minCount = (w * h * MIN_OVERLAP).toInt()
            for (dy in cy - ry..cy + ry) for (dx in cx - rx..cx + rx) {
                val x0 = maxOf(0, -dx); val x1 = minOf(w, w - dx)
                val y0 = maxOf(0, -dy); val y1 = minOf(h, h - dy)
                if ((x1 - x0) * (y1 - y0) < minCount) continue
                var sp = 0.0; var sc = 0.0; var n = 0
                for (y in y0 until y1) {
                    var ci = y * w + x0
                    var pi = (y + dy) * w + x0 + dx
                    for (x in x0 until x1) {
                        val p = prev[pi++]; val c = cur[ci++]
                        if (p >= 0f && c >= 0f) { sp += p; sc += c; n++ }
                    }
                }
                if (n < minCount) continue
                val mp = (sp / n).toFloat(); val mc = (sc / n).toFloat()
                var err = 0.0; var tex = 0.0
                for (y in y0 until y1) {
                    var ci = y * w + x0
                    var pi = (y + dy) * w + x0 + dx
                    for (x in x0 until x1) {
                        val p = prev[pi++]; val c = cur[ci++]
                        if (p >= 0f && c >= 0f) { err += abs((p - mp) - (c - mc)); tex += abs(p - mp) }
                    }
                }
                val e = (err / n).toFloat()
                if (best == null || e < best.error) {
                    val onEdge = dx == cx - rx || dx == cx + rx || (ry > 0 && (dy == cy - ry || dy == cy + ry))
                    best = Shift(dx, dy, e, (tex / n).toFloat(), if (mc > 1f) mp / mc else 1f, onEdge)
                }
            }
            return best
        }
    }
}
