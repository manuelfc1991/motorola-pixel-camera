package com.motorola.pixelcamera.computational

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PortraitPreviewTest {

    private val w = 120
    private val h = 160

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    private fun a(p: Int) = p ushr 24
    private fun r(p: Int) = (p shr 16) and 0xFF
    private fun g(p: Int) = (p shr 8) and 0xFF

    /** A red subject in the middle, a green and black striped background. */
    private val subject = { x: Int, y: Int -> x in 40 until 80 && y in 40 until 120 }
    private val pixels = IntArray(w * h) { i ->
        val x = i % w; val y = i / w
        when {
            subject(x, y) -> rgb(220, 30, 30)
            (x / 3) % 2 == 0 -> rgb(0, 200, 0)
            else -> rgb(0, 0, 0)
        }
    }
    private val mask = FloatArray(w * h) { i -> if (subject(i % w, i / w)) 1f else 0f }

    @Test
    fun subjectIsSeeThroughAndBackgroundCovered() {
        val out = PortraitPreview.overlay(pixels, mask, w, h, 14f)
        assertEquals(0, a(out[80 * w + 60]))
        assertEquals(255, a(out[10 * w + 10]))
    }

    @Test
    fun backgroundIsBlurred() {
        val out = PortraitPreview.overlay(pixels, mask, w, h, 26f)
        // Stripes 3 px wide average out towards half green.
        val greens = (0 until 12).map { g(out[10 * w + 10 + it]) }
        assertTrue("stripes should even out: $greens", greens.max() - greens.min() < 60)
    }

    @Test
    fun subjectColourDoesNotBleedIntoTheBackground() {
        val out = PortraitPreview.overlay(pixels, mask, w, h, 26f)
        // Just outside the red subject, the background stays free of red.
        assertTrue("red ${r(out[80 * w + 37])}", r(out[80 * w + 37]) < 20)
    }

    @Test
    fun radiusFollowsTheSliderAndGrid() {
        assertEquals(3, PortraitPreview.radiusFor(14f, 240))
        assertEquals(1, PortraitPreview.radiusFor(14f, 120))
        assertTrue(PortraitPreview.radiusFor(26f, 240) > PortraitPreview.radiusFor(2f, 240))
        assertEquals(1, PortraitPreview.radiusFor(2f, 120))
    }

    @Test
    fun wideScreenKeepsTheMiddleColumns() {
        val cols = PortraitPreview.cropForAspect(480, 640, 9f / 16f)
        assertEquals(360, cols.count())
        assertEquals(60, cols.first)
    }

    /** A 4 x 3 RGBA frame (row stride 20 bytes) whose red value is 10 * x + y. */
    private fun frame(): ByteArray {
        val buf = ByteArray(20 * 3)
        for (y in 0 until 3) for (x in 0 until 4) {
            buf[y * 20 + x * 4] = (10 * x + y).toByte(); buf[y * 20 + x * 4 + 3] = 0xFF.toByte()
        }
        return buf
    }

    @Test
    fun samplingTurnsTheFrameUpright() {
        // Rotated 90° clockwise the frame is 3 wide, 4 tall: its top-left is the source's bottom-left.
        val out = IntArray(12)
        PortraitPreview.sampleUpright(frame(), 20, 4, 3, 90, 0 until 3, 3, 4, out)
        val reds = out.map { r(it) }
        assertEquals(listOf(2, 1, 0, 12, 11, 10, 22, 21, 20, 32, 31, 30), reds)
        PortraitPreview.sampleUpright(frame(), 20, 4, 3, 270, 0 until 3, 3, 4, out)
        assertEquals(listOf(30, 31, 32, 20, 21, 22, 10, 11, 12, 0, 1, 2), out.map { r(it) })
        val flat = IntArray(12)
        PortraitPreview.sampleUpright(frame(), 20, 4, 3, 0, 0 until 4, 4, 3, flat)
        assertEquals(listOf(0, 10, 20, 30, 1, 11, 21, 31, 2, 12, 22, 32), flat.map { r(it) })
    }

    /** A 480 x 640 upright RGBA frame of a textured scene, shifted right by [shift] pixels. */
    private fun scene(shift: Int, brightness: Int = 0, seed: Int = 0): java.nio.ByteBuffer {
        val fw = 480; val fh = 640
        val bytes = ByteArray(fw * fh * 4)
        val noise = java.util.Random(seed.toLong())
        for (y in 0 until fh) for (x in 0 until fw) {
            val sx = x - shift
            val v = (128 + 60 * kotlin.math.sin(sx * 0.05) * kotlin.math.cos(y * 0.04) + brightness + noise.nextGaussian() * 3)
                .toInt().coerceIn(0, 255)
            val i = (y * fw + x) * 4
            bytes[i] = v.toByte(); bytes[i + 1] = v.toByte(); bytes[i + 2] = v.toByte(); bytes[i + 3] = -1
        }
        return java.nio.ByteBuffer.wrap(bytes)
    }

    private fun thumb(buf: java.nio.ByteBuffer) = PortraitPreview.motionThumb(buf, 480 * 4, 480, 640, 0, 0 until 480)

    @Test
    fun aStillPictureIsNotMotion() {
        // Same scene, different sensor noise.
        val change = PortraitPreview.changeBetween(thumb(scene(0, seed = 1)), thumb(scene(0, seed = 2)))
        assertTrue("noise read as $change", change < PortraitPreview.MOVED / 2)
    }

    @Test
    fun exposureSettlingIsNotMotion() {
        val change = PortraitPreview.changeBetween(thumb(scene(0)), thumb(scene(0, brightness = 25)))
        assertTrue("brightening read as $change", change < PortraitPreview.MOVED / 2)
    }

    @Test
    fun aSmallTurnIsMotion() {
        // 20 px of 480: a slow turn of the phone over a fraction of a second.
        val change = PortraitPreview.changeBetween(thumb(scene(0)), thumb(scene(20)))
        assertTrue("shift read as $change", change > PortraitPreview.MOVED)
    }

    @Test
    fun grainyScenesNeedMoreChange() {
        assertEquals(PortraitPreview.MOVED, PortraitPreview.movedThreshold(0.5f), 0f)
        assertEquals(7.5f, PortraitPreview.movedThreshold(3f), 1e-4f)
        assertEquals(12f, PortraitPreview.movedThreshold(50f), 0f)
    }

    @Test
    fun dimGrainIsNotMotion() {
        // Heavy grain (sd 20 per pixel) between two frames of the same scene.
        fun grainy(seed: Long): java.nio.ByteBuffer {
            val bytes = ByteArray(480 * 640 * 4)
            val n = java.util.Random(seed)
            for (i in 0 until 480 * 640) {
                val v = (40 + n.nextGaussian() * 20).toInt().coerceIn(0, 255).toByte()
                bytes[i * 4] = v; bytes[i * 4 + 1] = v; bytes[i * 4 + 2] = v; bytes[i * 4 + 3] = -1
            }
            return java.nio.ByteBuffer.wrap(bytes)
        }
        val a = thumb(grainy(1)); val b = thumb(grainy(2)); val c = thumb(grainy(3))
        val threshold = PortraitPreview.movedThreshold(PortraitPreview.changeBetween(a, b))
        assertTrue(PortraitPreview.changeBetween(a, c) < threshold)
    }
}
