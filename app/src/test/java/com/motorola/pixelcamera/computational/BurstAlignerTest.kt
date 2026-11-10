package com.motorola.pixelcamera.computational

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class BurstAlignerTest {

    private val w = 640
    private val h = 480

    /** Smooth random texture with sharp "text-like" edges, as an ARGB pixel array. */
    private fun scene(width: Int, height: Int, seed: Int = 1): IntArray {
        val rnd = Random(seed)
        val lum = IntArray(width * height) { 40 }
        // Random bright rectangles act as hard edges (like text strokes)
        repeat(400) {
            val rw = 2 + rnd.nextInt(12)
            val rh = 2 + rnd.nextInt(12)
            val x0 = rnd.nextInt(width - rw)
            val y0 = rnd.nextInt(height - rh)
            val v = 80 + rnd.nextInt(170)
            for (y in y0 until y0 + rh) for (x in x0 until x0 + rw) lum[y * width + x] = v
        }
        return IntArray(width * height) { i -> gray(lum[i]) }
    }

    private fun gray(v: Int): Int {
        val c = v.coerceIn(0, 255)
        return (0xFF shl 24) or (c shl 16) or (c shl 8) or c
    }

    private fun lum(p: Int) = (p shr 8) and 0xFF

    /** Crop a w*h window from a bigger scene at (ox, oy), adding sensor noise. */
    private fun frameFrom(big: IntArray, bigW: Int, ox: Int, oy: Int, noise: Int, seed: Int): IntArray {
        val rnd = Random(seed)
        return IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val v = lum(big[(y + oy) * bigW + (x + ox)])
            gray(v + rnd.nextInt(-noise, noise + 1))
        }
    }

    @Test
    fun estimatesHandShakeOffsets() {
        val bigW = w + 200
        val big = scene(bigW, h + 200)
        val ref = frameFrom(big, bigW, 100, 100, 6, 10)
        val shifts = listOf(0 to 0, 23 to -17, -41 to 9, 7 to 52)
        val frames = shifts.mapIndexed { k, (dx, dy) -> frameFrom(big, bigW, 100 + dx, 100 + dy, 6, 20 + k) }

        for ((k, s) in shifts.withIndex()) {
            val est = BurstAligner.estimateOffset(ref, frames[k], w, h)
            // Frame content at (x) equals reference at (x + dx), so the frame must be sampled at -dx.
            assertEquals("dx frame $k", -s.first, est.first)
            assertEquals("dy frame $k", -s.second, est.second)
        }
    }

    @Test
    fun alignedMergeHasNoGhosting() {
        val bigW = w + 200
        val big = scene(bigW, h + 200)
        val clean = frameFrom(big, bigW, 100, 100, 0, 0)
        val shifts = listOf(0 to 0, 23 to -17, -41 to 9, 7 to 52)
        val frames = shifts.mapIndexed { k, (dx, dy) -> frameFrom(big, bigW, 100 + dx, 100 + dy, 10, 30 + k) }

        val merged = BurstAligner.alignAndMerge(frames, w, h, referenceIndex = 0)

        // Compare inside a margin that every shifted frame covers
        fun meanErr(img: IntArray): Double {
            var sum = 0.0
            var n = 0
            for (y in 60 until h - 60) for (x in 60 until w - 60) {
                sum += abs(lum(img[y * w + x]) - lum(clean[y * w + x])); n++
            }
            return sum / n
        }
        val singleErr = meanErr(frames[0])
        val mergedErr = meanErr(merged)
        // Merged must be cleaner than any single noisy frame (noise averaged, no ghost copies)
        assertTrue("merged error $mergedErr should be below single-frame error $singleErr", mergedErr < singleErr * 0.75)
    }

    @Test
    fun movingObjectDoesNotGhost() {
        val base = scene(w, h, seed = 5)
        val frames = (0 until 4).map { k ->
            val f = base.copyOf()
            // A bright square that moves between frames (e.g. a walking person)
            val ox = 100 + k * 60
            for (y in 200 until 260) for (x in ox until ox + 40) f[y * w + x] = gray(250)
            f
        }
        val merged = BurstAligner.alignAndMerge(frames, w, h, referenceIndex = 0)
        // Where the object was only in other frames, the reference background must survive
        val x = 100 + 2 * 60 + 20
        val y = 230
        val diff = abs(lum(merged[y * w + x]) - lum(base[y * w + x]))
        assertTrue("ghost of moving object visible (diff=$diff)", diff < 20)
    }

    @Test
    fun picksSharpestFrameAsReference() {
        val sharp = scene(w, h)
        // Blurred copy: 5x5 box blur simulates a shaky frame
        val blurred = IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            var s = 0; var n = 0
            for (yy in y - 2..y + 2) for (xx in x - 2..x + 2) {
                if (xx in 0 until w && yy in 0 until h) { s += lum(sharp[yy * w + xx]); n++ }
            }
            gray(s / n)
        }
        assertEquals(1, BurstAligner.sharpestIndex(listOf(blurred, sharp, blurred), w, h))
    }
}
