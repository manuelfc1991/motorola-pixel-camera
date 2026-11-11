package com.motorola.pixelcamera.computational

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

class PanoramaStitcherTest {

    private val fw = 240
    private val fh = 320
    private val ppd = 10f

    /** A wide made-up scene with detail at several sizes, so every part of it looks different. */
    private val sceneW = 2600
    private val sceneH = 400
    private val scene: IntArray = run {
        val rnd = Random(7)
        val blobs = List(500) { Triple(rnd.nextInt(sceneW), rnd.nextInt(sceneH), 4 + rnd.nextInt(30)) }
        val v = FloatArray(sceneW * sceneH) { i ->
            val x = i % sceneW; val y = i / sceneW
            (110 + 30 * sin(x / 37.0) * sin(y / 23.0) + 20 * sin(x / 9.0 + y / 13.0)).toFloat()
        }
        for ((bx, by, r) in blobs) {
            val tone = rnd.nextInt(-60, 60)
            for (y in maxOf(0, by - r) until minOf(sceneH, by + r)) for (x in maxOf(0, bx - r) until minOf(sceneW, bx + r)) {
                if ((x - bx) * (x - bx) + (y - by) * (y - by) < r * r) v[y * sceneW + x] += tone
            }
        }
        IntArray(sceneW * sceneH) { i ->
            val g = v[i].toInt().coerceIn(0, 255)
            (0xFF shl 24) or (g shl 16) or ((g * 3 / 4) shl 8) or (255 - g)
        }
    }

    private fun frameAt(ox: Int, oy: Int, gain: Float = 1f) = IntArray(fw * fh) { i ->
        val p = scene[(oy + i / fw) * sceneW + ox + i % fw]
        fun c(s: Int) = (((p shr s) and 0xFF) * gain).toInt().coerceIn(0, 255)
        (0xFF shl 24) or (c(16) shl 16) or (c(8) shl 8) or c(0)
    }

    private fun g(p: Int) = (p shr 16) and 0xFF

    /** Mean difference of the stitched picture against the scene, placed where it matches best near [expectX]. */
    private fun bestMatchError(img: PanoramaStitcher.Image, expectX: Int): Pair<Int, Float> {
        var best = Float.MAX_VALUE
        var bestX = -1
        for (ox in maxOf(0, expectX - 10)..minOf(sceneW - img.width, expectX + 10)) for (oy in 0..sceneH - img.height) {
            var err = 0L; var n = 0
            for (y in 0 until img.height step 5) for (x in 0 until img.width step 5) {
                err += abs(g(img.pixels[y * img.width + x]) - g(scene[(oy + y) * sceneW + ox + x])); n++
            }
            val e = err.toFloat() / n
            if (e < best) { best = e; bestX = ox }
        }
        return bestX to best
    }

    private fun sweep(stitcher: PanoramaStitcher, offsets: List<Int>, yawError: Float = 0f): Int {
        var used = 0
        for ((k, ox) in offsets.withIndex()) {
            // The gyro is off by a little and the hand drifts up and down.
            val yaw = (ox - offsets[0]) / ppd * (1 + yawError)
            val oy = 40 + (if (k % 2 == 0) 0 else 6)
            if (stitcher.add(frameAt(ox, oy), yaw)) used++
        }
        return used
    }

    @Test
    fun findsAKnownShift() {
        val s = PanoramaStitcher(fw, fh, 0f, ppd)
        val a = s.luma(frameAt(100, 40), 2)
        val b = s.luma(frameAt(160, 44), 2)
        // b shows what a shows 30 columns and 2 rows further on, at half size.
        val shift = PanoramaStitcher.estimateShift(a, b, fw / 2, fh / 2, 25, 0, 10, 4)!!
        assertEquals(30, shift.dx)
        assertEquals(2, shift.dy)
    }

    @Test
    fun exposureChangeDoesNotMoveTheMatch() {
        val s = PanoramaStitcher(fw, fh, 0f, ppd)
        val a = s.luma(frameAt(100, 40), 2)
        val b = s.luma(frameAt(140, 40, gain = 0.8f), 2)
        val shift = PanoramaStitcher.estimateShift(a, b, fw / 2, fh / 2, 15, 0, 10, 3)!!
        assertEquals(20, shift.dx)
        assertEquals(0, shift.dy)
        assertEquals(1.25f, shift.gain, 0.05f)
    }

    @Test
    fun rightPanRebuildsTheSceneWithoutRepeats() {
        val s = PanoramaStitcher(fw, fh, 0f, ppd)
        // The gyro reads 15 % short: the picture match has to put each frame in the right place.
        val offsets = (0..24).map { 200 + it * 60 }
        val used = sweep(s, offsets, yawError = -0.15f)
        assertTrue("used $used frames", used >= 12)
        val img = s.result()!!
        val expectedW = offsets.last() - offsets.first() + fw
        assertEquals(expectedW.toFloat(), img.width.toFloat(), 8f)
        val (x, err) = bestMatchError(img, 200)
        assertEquals(200f, x.toFloat(), 4f)
        assertTrue("stitched picture differs from the scene by $err", err < 4f)
    }

    @Test
    fun leftPanWorksToo() {
        val s = PanoramaStitcher(fw, fh, 0f, ppd)
        val offsets = (0..24).map { 1900 - it * 60 }
        sweep(s, offsets)
        assertEquals(-1, s.direction)
        val img = s.result()!!
        assertEquals((1900 - 460 + fw).toFloat(), img.width.toFloat(), 8f)
        val (x, err) = bestMatchError(img, 460)
        assertEquals(460f, x.toFloat(), 4f)
        assertTrue("stitched picture differs from the scene by $err", err < 4f)
    }

    @Test
    fun holdingStillAddsNothing() {
        val s = PanoramaStitcher(fw, fh, 0f, ppd)
        assertTrue(s.add(frameAt(300, 40), 0f))
        repeat(5) { assertFalse(s.add(frameAt(302, 40), 1f)) }
        assertEquals(1, s.frameCount)
        assertEquals(null, s.result())
    }

    @Test
    fun onlyAsksForFramesOnceTheViewHasMoved() {
        val s = PanoramaStitcher(fw, fh, 0f, ppd)
        assertTrue(s.wantsFrame(0f))
        s.add(frameAt(300, 40), 0f)
        assertFalse(s.wantsFrame(2f))
        assertTrue(s.wantsFrame(fw * PanoramaStitcher.STEP / ppd + 0.1f))
        assertTrue(s.wantsFrame(-(fw * PanoramaStitcher.STEP / ppd + 0.1f)))
    }

    @Test
    fun cylinderKeepsTheCentreAndBlanksTheCorners() {
        val s = PanoramaStitcher(fw, fh, fw * 0.93f, ppd)
        val frame = frameAt(500, 40)
        val cyl = s.project(frame)
        val centre = (fh / 2) * fw + fw / 2
        assertEquals(g(frame[centre]).toFloat(), g(cyl[centre]).toFloat(), 3f)
        assertEquals(0, cyl[0] ushr 24)
        assertEquals(0, cyl[fw * fh - 1] ushr 24)
    }

    @Test
    fun curvedFramesStillGiveAPanorama() {
        val s = PanoramaStitcher(fw, fh, fw * 0.93f, ppd)
        sweep(s, (0..20).map { 200 + it * 60 })
        val img = s.result()
        assertNotNull(img)
        img!!
        for (p in img.pixels) assertEquals(0xFF, p ushr 24)
        assertTrue(img.width > 1000)
    }
}
