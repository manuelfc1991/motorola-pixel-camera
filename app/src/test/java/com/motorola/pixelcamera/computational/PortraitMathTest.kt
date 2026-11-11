package com.motorola.pixelcamera.computational

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PortraitMathTest {

    @Test
    fun `subject colour does not bleed into the blurred background`() {
        // Left half: bright subject (255). Right half: dark background (10).
        val w = 40
        val h = 8
        val channel = FloatArray(w * h) { if (it % w < w / 2) 255f else 10f }
        val background = FloatArray(w * h) { if (it % w < w / 2) 0f else 1f }

        val blurred = PortraitMath.backgroundBlur(channel, background, w, h, radius = 6)

        // Right at the outline a plain blur would give about 130; ours stays background-dark.
        assertEquals(10f, blurred[3 * w + w / 2], 0.5f)
    }

    @Test
    fun `subject stays sharp and blur grows with distance behind it`() {
        // 64 x 64: subject on the left, then columns ever farther behind it.
        val w = 64
        val h = 64
        val alpha = FloatArray(w * h) { if (it % w < 8) 1f else 0f }
        val depth = FloatArray(w * h) { 0.9f - (it % w) / 64f * 0.8f }

        val amount = PortraitMath.blurAmount(alpha, depth, subjectDepth = 0.9f, w, h)

        val row = 32 * w
        assertEquals(0f, amount[row + 2], 1e-6f)                 // the subject itself
        assertTrue(amount[row + 20] < amount[row + 40])          // farther means blurrier
        assertEquals(1f, amount[row + 60], 0.02f)                // far behind: full blur
    }

    @Test
    fun `things in front of the subject blur less than things behind it`() {
        val w = 64
        val h = 64
        val front = PortraitMath.blurAmount(null, FloatArray(w * h) { 0.9f }, subjectDepth = 0.5f, w, h)
        val behind = PortraitMath.blurAmount(null, FloatArray(w * h) { 0.1f }, subjectDepth = 0.5f, w, h)
        assertTrue(front[0] > 0f && front[0] < behind[0])
    }

    @Test
    fun `without depth everything outside the mask is fully blurred`() {
        val amount = PortraitMath.blurAmount(floatArrayOf(1f, 0.25f, 0f), null, 0f, 3, 1)
        assertEquals(0f, amount[0], 1e-6f)
        assertEquals(0.75f, amount[1], 1e-6f)
        assertEquals(1f, amount[2], 1e-6f)
    }

    @Test
    fun `only the focused object stays in the subject mask`() {
        // Two separate blobs and a third one touching the first but far behind it.
        val w = 60
        val h = 40
        fun inBox(i: Int, x0: Int, x1: Int) = i % w in x0..x1 && i / w in 5..34
        val alpha = FloatArray(w * h) { if (inBox(it, 5, 24) || inBox(it, 25, 34) || inBox(it, 45, 54)) 1f else 0f }
        val depth = FloatArray(w * h) { if (inBox(it, 25, 34)) 0.3f else 0.8f }

        val kept = PortraitMath.isolateSubject(alpha, depth, w, h, focusX = 14f / w, focusY = 0.5f)

        assertEquals(1f, kept[20 * w + 14], 1e-6f)  // focused object
        assertEquals(0f, kept[20 * w + 33], 1e-6f)  // touching it, but well behind
        assertEquals(0f, kept[20 * w + 50], 1e-6f)  // separate object
    }

    @Test
    fun `transpose swaps rows and columns and back`() {
        val src = floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f) // 3 wide, 2 tall
        val flipped = PortraitMath.transpose(src, 3, 2) // 2 wide, 3 tall
        assertEquals(listOf(1f, 4f, 2f, 5f, 3f, 6f), flipped.toList())
        assertEquals(src.toList(), PortraitMath.transpose(flipped, 2, 3).toList())
    }

    @Test
    fun `resize keeps a flat image flat and normalise stretches to 0-1`() {
        val flat = PortraitMath.resizeBilinear(FloatArray(16) { 0.5f }, 4, 4, 9, 7)
        assertTrue(flat.all { kotlin.math.abs(it - 0.5f) < 1e-6f })
        assertEquals(listOf(0f, 0.5f, 1f), PortraitMath.normalise(floatArrayOf(-4f, 0f, 4f)).toList())
    }
}
