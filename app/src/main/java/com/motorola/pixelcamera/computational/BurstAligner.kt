package com.motorola.pixelcamera.computational

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Aligns and merges handheld burst frames (pure Kotlin, ARGB IntArrays, unit-testable).
 *
 * Handheld frames are captured hundreds of milliseconds apart, so each one is shifted
 * by tens of pixels. Averaging them unaligned produces "ghost" copies of every edge.
 *
 * 1. Global translation per frame, found coarse-to-fine on a luma pyramid.
 * 2. Robust merge: areas where an aligned frame still disagrees with the reference
 *    (moving subjects, rotation at the edges) fall back to the reference frame.
 */
object BurstAligner {

    private const val COARSE_RADIUS = 12
    private const val REFINE_RADIUS = 2
    private const val MAX_SAMPLES = 250_000
    private const val WEIGHT_BLOCK = 4
    private const val WEIGHT_ONE = 64
    private const val FEATHER_BLOCKS = 3
    private const val GAIN_SHIFT = 10
    private const val GAIN_ONE = 1 shl GAIN_SHIFT
    private const val GAIN_HALF = GAIN_ONE / 2
    private const val MIN_GAIN = 0.8f
    private const val MAX_GAIN = 1.25f

    private fun lumaOf(c: Int): Int =
        (((c shr 16) and 0xFF) * 77 + ((c shr 8) and 0xFF) * 150 + (c and 0xFF) * 29) shr 8

    /** Luma 0..255 from ARGB, stored as unsigned bytes. */
    private fun luma(px: IntArray): ByteArray = ByteArray(px.size) { i -> lumaOf(px[i]).toByte() }

    private class Plane(val data: ByteArray, val w: Int, val h: Int) {
        operator fun get(i: Int): Int = data[i].toInt() and 0xFF
    }

    private fun halve(p: Plane): Plane {
        val w2 = max(1, p.w / 2)
        val h2 = max(1, p.h / 2)
        val out = ByteArray(w2 * h2)
        for (y in 0 until h2) {
            val r0 = (2 * y) * p.w
            val r1 = min(2 * y + 1, p.h - 1) * p.w
            for (x in 0 until w2) {
                val x0 = 2 * x
                val x1 = min(2 * x + 1, p.w - 1)
                out[y * w2 + x] = ((p[r0 + x0] + p[r0 + x1] + p[r1 + x0] + p[r1 + x1] + 2) shr 2).toByte()
            }
        }
        return Plane(out, w2, h2)
    }

    /** Pyramid from full resolution (index 0) down to the coarsest level. */
    private fun pyramid(l: ByteArray, w: Int, h: Int, levels: Int): List<Plane> {
        val out = ArrayList<Plane>(levels + 1)
        out.add(Plane(l, w, h))
        repeat(levels) { out.add(halve(out.last())) }
        return out
    }

    private fun pyramidLevels(w: Int, h: Int): Int {
        var levels = 0
        // Keep the coarsest level at least ~48 px on its short side, at most 1/16 scale
        while (levels < 4 && min(w, h) shr (levels + 1) >= 48) levels++
        return levels
    }

    /** Mean absolute difference of ref(x,y) vs frame(x+dx, y+dy) over the overlap. */
    private fun sad(ref: Plane, frm: Plane, dx: Int, dy: Int): Double {
        val x0 = max(0, -dx)
        val x1 = min(ref.w, frm.w - dx)
        val y0 = max(0, -dy)
        val y1 = min(ref.h, frm.h - dy)
        if (x1 - x0 < 8 || y1 - y0 < 8) return Double.MAX_VALUE
        val area = (x1 - x0).toLong() * (y1 - y0)
        var stride = 1
        while (area / (stride.toLong() * stride) > MAX_SAMPLES) stride++
        var sum = 0L
        var n = 0
        var y = y0
        while (y < y1) {
            val rRow = y * ref.w
            val fRow = (y + dy) * frm.w + dx
            var x = x0
            while (x < x1) {
                sum += abs(ref[rRow + x] - frm[fRow + x])
                n++
                x += stride
            }
            y += stride
        }
        return sum.toDouble() / n
    }

    private fun search(ref: Plane, frm: Plane, cx: Int, cy: Int, radius: Int): Pair<Int, Int> {
        var best = Double.MAX_VALUE
        var bx = cx
        var by = cy
        for (dy in cy - radius..cy + radius) {
            for (dx in cx - radius..cx + radius) {
                val s = sad(ref, frm, dx, dy)
                // Prefer the smaller shift on ties so static scenes stay at (0,0)
                if (s < best || (s == best && abs(dx) + abs(dy) < abs(bx) + abs(by))) {
                    best = s; bx = dx; by = dy
                }
            }
        }
        return bx to by
    }

    private fun estimateOffset(refPyr: List<Plane>, frmPyr: List<Plane>): Pair<Int, Int> {
        val top = refPyr.size - 1
        var (dx, dy) = search(refPyr[top], frmPyr[top], 0, 0, COARSE_RADIUS)
        for (level in top - 1 downTo 0) {
            val r = search(refPyr[level], frmPyr[level], dx * 2, dy * 2, REFINE_RADIUS)
            dx = r.first; dy = r.second
        }
        return dx to dy
    }

    /**
     * Offset (dx, dy) such that frame(x + dx, y + dy) lines up with ref(x, y).
     */
    fun estimateOffset(ref: IntArray, frame: IntArray, w: Int, h: Int): Pair<Int, Int> {
        val levels = pyramidLevels(w, h)
        return estimateOffset(pyramid(luma(ref), w, h, levels), pyramid(luma(frame), w, h, levels))
    }

    /** Laplacian energy: higher means sharper (less hand shake inside the exposure). */
    fun sharpness(px: IntArray, w: Int, h: Int): Double {
        var stride = 1
        while ((w.toLong() / stride) * (h / stride) > 1_000_000) stride++
        var sum = 0.0
        var n = 0
        var y = 1
        while (y < h - 1) {
            var x = 1
            while (x < w - 1) {
                val i = y * w + x
                val lap = 4 * lumaOf(px[i]) - lumaOf(px[i - 1]) - lumaOf(px[i + 1]) -
                    lumaOf(px[i - w]) - lumaOf(px[i + w])
                sum += lap.toDouble() * lap
                n++
                x += stride
            }
            y += stride
        }
        return if (n > 0) sum / n else 0.0
    }

    /** Index of the sharpest frame — the best reference for the merge. */
    fun sharpestIndex(frames: List<IntArray>, w: Int, h: Int): Int =
        frames.indices.maxByOrNull { sharpness(frames[it], w, h) } ?: 0

    private fun blockMeans(l: ByteArray, w: Int, h: Int, dx: Int, dy: Int, bw: Int, bh: Int): IntArray {
        // Block average of frame shifted by (dx, dy); -1 marks blocks outside the frame
        val out = IntArray(bw * bh)
        for (by in 0 until bh) for (bx in 0 until bw) {
            var s = 0; var n = 0; var outside = false
            for (yy in by * WEIGHT_BLOCK until min(h, (by + 1) * WEIGHT_BLOCK)) {
                val sy = yy + dy
                for (xx in bx * WEIGHT_BLOCK until min(w, (bx + 1) * WEIGHT_BLOCK)) {
                    val sx = xx + dx
                    if (sx < 0 || sy < 0 || sx >= w || sy >= h) { outside = true; continue }
                    s += l[sy * w + sx].toInt() and 0xFF; n++
                }
            }
            out[by * bw + bx] = if (outside || n == 0) -1 else s / n
        }
        return out
    }

    /**
     * Widens every rejected area by one block and then fades trust in over a few blocks.
     * A hard edge between "merged" and "reference only" is visible even when the two differ
     * by a single brightness level; a gradual one is not.
     */
    private fun feather(weight: IntArray, bw: Int, bh: Int): IntArray {
        val shrunk = IntArray(weight.size)
        for (y in 0 until bh) for (x in 0 until bw) {
            var m = Int.MAX_VALUE
            for (yy in max(0, y - 1)..min(bh - 1, y + 1)) for (xx in max(0, x - 1)..min(bw - 1, x + 1)) {
                m = min(m, weight[yy * bw + xx])
            }
            shrunk[y * bw + x] = m
        }
        // Separable box blur; never raise a block above what it had, so rejected stays rejected.
        val temp = IntArray(weight.size)
        val div = 2 * FEATHER_BLOCKS + 1
        for (y in 0 until bh) for (x in 0 until bw) {
            var sum = 0
            for (k in -FEATHER_BLOCKS..FEATHER_BLOCKS) sum += shrunk[y * bw + (x + k).coerceIn(0, bw - 1)]
            temp[y * bw + x] = sum / div
        }
        val out = IntArray(weight.size)
        for (y in 0 until bh) for (x in 0 until bw) {
            var sum = 0
            for (k in -FEATHER_BLOCKS..FEATHER_BLOCKS) sum += temp[(y + k).coerceIn(0, bh - 1) * bw + x]
            out[y * bw + x] = min(sum / div, weight[y * bw + x])
        }
        return out
    }

    /** A frame after alignment: its pixels, its shift, per-block trust in 1/[WEIGHT_ONE] steps,
     *  and the brightness gain (in 1/[GAIN_ONE] steps) that matches it to the reference. */
    private class Aligned(val px: IntArray, val dx: Int, val dy: Int, val blockWeight: IntArray, val gain: Int)

    private fun parallelFor(count: Int, body: (Int) -> Unit) =
        java.util.stream.IntStream.range(0, count).parallel().forEach { body(it) }

    /**
     * Align every frame to [referenceIndex] and merge with ghost rejection.
     * Returns a new ARGB array the same size as the reference.
     */
    fun alignAndMerge(frames: List<IntArray>, w: Int, h: Int, referenceIndex: Int): IntArray =
        alignAndMerge(frames.size, w, h, referenceIndex) { frames[it] }

    /**
     * [frameAt] is called once per frame, possibly from several threads at once.
     * Frames are analysed in parallel, then merged row by row in parallel.
     */
    fun alignAndMerge(frameCount: Int, w: Int, h: Int, referenceIndex: Int, frameAt: (Int) -> IntArray): IntArray {
        require(frameCount > 0) { "No frames" }
        val ref = frameAt(referenceIndex)
        if (frameCount == 1) return ref.copyOf()

        val levels = pyramidLevels(w, h)
        val refLuma = luma(ref)
        val refPyr = pyramid(refLuma, w, h, levels)

        val bw = (w + WEIGHT_BLOCK - 1) / WEIGHT_BLOCK
        val bh = (h + WEIGHT_BLOCK - 1) / WEIGHT_BLOCK
        val refBlocks = blockMeans(refLuma, w, h, 0, 0, bw, bh)

        val others = (0 until frameCount).filter { it != referenceIndex }
        val slots = arrayOfNulls<Aligned>(others.size)
        parallelFor(others.size) { n ->
            val frame = frameAt(others[n])
            val frmLuma = luma(frame)
            val (dx, dy) = estimateOffset(refPyr, pyramid(frmLuma, w, h, levels))
            val frmBlocks = blockMeans(frmLuma, w, h, dx, dy, bw, bh)

            // Frames of one burst differ a little in brightness (exposure still settling, lamp
            // flicker). Match this frame to the reference first; otherwise every area where the
            // frame is rejected shows up as a patch of slightly different brightness.
            var refSum = 0L
            var frmSum = 0L
            for (b in 0 until bw * bh) {
                if (frmBlocks[b] >= 0) { refSum += refBlocks[b]; frmSum += frmBlocks[b] }
            }
            val gain = if (frmSum > 0) (refSum.toFloat() / frmSum).coerceIn(MIN_GAIN, MAX_GAIN) else 1f

            // Noise-adaptive threshold from the median block difference
            val diffs = IntArray(bw * bh)
            var nd = 0
            for (b in 0 until bw * bh) {
                if (frmBlocks[b] >= 0) diffs[nd++] = abs(refBlocks[b] - (frmBlocks[b] * gain + 0.5f).toInt())
            }
            if (nd > 0) {
                val sorted = diffs.copyOf(nd).also { it.sort() }
                val t = 1.5f * sorted[nd / 2] + 4f
                val blockWeight = IntArray(bw * bh) { b ->
                    if (frmBlocks[b] < 0) 0 else {
                        val d = abs(refBlocks[b] - (frmBlocks[b] * gain + 0.5f).toInt())
                        val trust = when {
                            d <= t -> 1f
                            d >= 3f * t -> 0f
                            else -> 1f - (d - t) / (2f * t)
                        }
                        (trust * WEIGHT_ONE + 0.5f).toInt()
                    }
                }
                slots[n] = Aligned(frame, dx, dy, feather(blockWeight, bw, bh), (gain * GAIN_ONE + 0.5f).toInt())
            }
        }
        val aligned = slots.filterNotNull()

        val out = IntArray(w * h)
        parallelFor(h) { y ->
            val bRow = (y / WEIGHT_BLOCK) * bw
            for (x in 0 until w) {
                val i = y * w + x
                val c = ref[i]
                var r = ((c shr 16) and 0xFF) * WEIGHT_ONE
                var g = ((c shr 8) and 0xFF) * WEIGHT_ONE
                var b = (c and 0xFF) * WEIGHT_ONE
                var weight = WEIGHT_ONE
                for (a in aligned) {
                    val sy = y + a.dy
                    val sx = x + a.dx
                    if (sy < 0 || sy >= h || sx < 0 || sx >= w) continue
                    val wq = a.blockWeight[bRow + x / WEIGHT_BLOCK]
                    if (wq <= 0) continue
                    val f = a.px[sy * w + sx]
                    r += wq * min(255, (((f shr 16) and 0xFF) * a.gain + GAIN_HALF) shr GAIN_SHIFT)
                    g += wq * min(255, (((f shr 8) and 0xFF) * a.gain + GAIN_HALF) shr GAIN_SHIFT)
                    b += wq * min(255, ((f and 0xFF) * a.gain + GAIN_HALF) shr GAIN_SHIFT)
                    weight += wq
                }
                val half = weight / 2
                out[i] = (0xFF shl 24) or (((r + half) / weight) shl 16) or (((g + half) / weight) shl 8) or ((b + half) / weight)
            }
        }
        return out
    }
}
