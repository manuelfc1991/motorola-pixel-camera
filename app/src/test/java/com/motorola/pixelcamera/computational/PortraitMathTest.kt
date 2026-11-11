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
    fun `subject stays sharp and blur grows with depth distance`() {
        val alpha = floatArrayOf(1f, 0f, 0f, 0f)
        val depth = floatArrayOf(0.8f, 0.8f, 0.6f, 0.1f)

        val amount = PortraitMath.blurAmount(alpha, depth, subjectDepth = 0.8f)

        assertEquals(0f, amount[0], 1e-6f)          // the subject itself
        assertEquals(0f, amount[1], 1e-6f)          // same distance as the subject
        assertTrue(amount[2] > 0f && amount[2] < 1f) // a little behind
        assertEquals(1f, amount[3], 1e-6f)          // far behind
    }

    @Test
    fun `without depth everything outside the mask is fully blurred`() {
        val amount = PortraitMath.blurAmount(floatArrayOf(1f, 0.25f, 0f), null, 0f)
        assertEquals(0f, amount[0], 1e-6f)
        assertEquals(0.75f, amount[1], 1e-6f)
        assertEquals(1f, amount[2], 1e-6f)
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
