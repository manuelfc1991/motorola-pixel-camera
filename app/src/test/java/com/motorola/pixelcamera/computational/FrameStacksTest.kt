package com.motorola.pixelcamera.computational

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameStacksTest {

    private val w = 200
    private val h = 160

    private fun grey(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    private fun r(p: Int) = (p shr 16) and 0xFF
    private fun g(p: Int) = (p shr 8) and 0xFF

    @Test
    fun longExposureAveragesTheFrames() {
        val stack = LongExposureStack()
        for (v in listOf(60, 80, 100)) stack.add(IntArray(w * h) { grey(v) }, w, h)
        val out = stack.resultPixels()!!
        assertEquals(3, stack.count)
        assertEquals(80f, g(out[w * h / 2]).toFloat(), 1f)
    }

    @Test
    fun longExposureKeepsAMovingLightBright() {
        val stack = LongExposureStack()
        // A bright light crosses a dark street: it sits on each pixel of its path in one frame only.
        repeat(10) { k ->
            stack.add(IntArray(w * h) { i -> if (i / w == 80 && i % w == 20 + k * 10) grey(250) else grey(20) }, w, h)
        }
        val out = stack.resultPixels()!!
        val trail = g(out[80 * w + 50])
        val plainAverage = (250 + 9 * 20) / 10
        assertTrue("trail $trail should be well above the plain average $plainAverage", trail > plainAverage + 50)
        assertEquals(20f, g(out[10 * w + 10]).toFloat(), 1f)
    }

    @Test
    fun framesOfAnotherSizeAreSkipped() {
        val stack = LongExposureStack()
        stack.add(IntArray(w * h) { grey(50) }, w, h)
        stack.add(IntArray(100 * 100) { grey(250) }, 100, 100)
        assertEquals(1, stack.count)
    }

    /** A dark sky with stars on the 16 px detection grid, all moved by ([dx], [dy]). */
    private fun sky(dx: Int, dy: Int) = IntArray(w * h) { grey(10) }.also { px ->
        for ((sx, sy) in listOf(48 to 32, 96 to 64, 144 to 112, 64 to 128)) {
            px[(sy + dy) * w + sx + dx] = grey(240)
        }
    }

    @Test
    fun astroLinesTheStarsUpAgain() {
        val stack = AstroStack()
        stack.add(sky(0, 0), w, h)
        // Later frames are exactly one grid step off, so their stars are still found.
        stack.add(sky(16, 0), w, h)
        stack.add(sky(16, 16), w, h)
        val out = stack.resultPixels()!!
        // Each star stays as bright as in one frame (boosted), not smeared over three places.
        assertTrue("star at its first-frame place: ${r(out[32 * w + 48])}", r(out[32 * w + 48]) > 200)
        assertTrue("no ghost where the second frame had it: ${r(out[32 * w + 64])}", r(out[32 * w + 64]) < 30)
    }

    @Test
    fun astroDarkensTheSkyGlow() {
        val stack = AstroStack()
        repeat(3) { stack.add(IntArray(w * h) { grey(30) }, w, h) }
        assertEquals((30 * 0.88f).toInt().toFloat(), g(stack.resultPixels()!![500]).toFloat(), 1f)
    }
}
