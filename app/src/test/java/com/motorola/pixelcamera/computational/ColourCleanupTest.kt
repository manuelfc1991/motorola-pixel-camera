package com.motorola.pixelcamera.computational

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class ColourCleanupTest {

    private val w = 400
    private val h = 320

    private fun rgb(r: Int, g: Int, b: Int) =
        (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    private fun r(p: Int) = (p shr 16) and 0xFF
    private fun g(p: Int) = (p shr 8) and 0xFF
    private fun b(p: Int) = p and 0xFF
    private fun luma(p: Int) = 0.299f * r(p) + 0.587f * g(p) + 0.114f * b(p)

    /** Colour patches a few tens of pixels wide on a flat grey surface: what a high-ISO merge leaves behind. */
    private fun blotch(x: Int, y: Int): Int = (6 * sin(x / 4.0) * sin(y / 5.0)).toInt()

    private fun redSpread(pixels: IntArray, x0: Int, x1: Int): Double {
        var sum = 0.0
        var sq = 0.0
        var n = 0
        for (y in 40 until h - 40) for (x in x0 until x1) {
            val v = (r(pixels[y * w + x]) - g(pixels[y * w + x])).toDouble()
            sum += v; sq += v * v; n++
        }
        return sqrt(sq / n - (sum / n) * (sum / n))
    }

    @Test
    fun colourBlotchesOnAFlatSurfaceAreFlattened() {
        val pixels = IntArray(w * h) { i -> val d = blotch(i % w, i / w); rgb(120 + d, 120, 120 - d) }
        val before = redSpread(pixels, 40, w - 40)
        ColourCleanup.clean(pixels, w, h, 1f)
        val after = redSpread(pixels, 40, w - 40)
        assertTrue("blotches should shrink to under a third: $before -> $after", after < before / 3)
    }

    @Test
    fun brightnessIsLeftAlone() {
        val original = IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            val d = blotch(x, y)
            val v = 60 + ((x * 7 + y * 13) % 120)   // busy brightness texture
            rgb(v + d, v, v - d)
        }
        val pixels = original.clone()
        ColourCleanup.clean(pixels, w, h, 1f)
        var worst = 0f
        for (i in pixels.indices) worst = maxOf(worst, abs(luma(pixels[i]) - luma(original[i])))
        assertTrue("brightness moved by $worst", worst <= 1.5f)
    }

    @Test
    fun colourEdgeStaysSharpAndDoesNotBleed() {
        // Red left half, blue right half, same brightness, each with blotches.
        val pixels = IntArray(w * h) { i ->
            val x = i % w; val d = blotch(x, i / w)
            if (x < w / 2) rgb(200 + d, 90, 60 - d) else rgb(60 + d, 110, 200 - d)
        }
        ColourCleanup.clean(pixels, w, h, 1f)
        val y = h / 2
        val left = pixels[y * w + w / 2 - 6]
        val right = pixels[y * w + w / 2 + 6]
        assertTrue("left of the edge stays red: ${r(left)},${b(left)}", r(left) > 180 && b(left) < 80)
        assertTrue("right of the edge stays blue: ${r(right)},${b(right)}", b(right) > 180 && r(right) < 80)
    }

    @Test
    fun smallColouredDetailSurvives() {
        // A 6 px saturated red dot on grey must not be washed out.
        val pixels = IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            if (abs(x - 200) < 3 && abs(y - 160) < 3) rgb(230, 40, 40) else rgb(120, 120, 120)
        }
        ColourCleanup.clean(pixels, w, h, 1f)
        val dot = pixels[160 * w + 200]
        assertTrue("dot keeps its colour: ${r(dot)},${g(dot)}", r(dot) - g(dot) > 150)
    }

    @Test
    fun strengthFollowsIso() {
        assertEquals(0f, ColourCleanup.strengthForIso(400), 0f)
        assertEquals(0f, ColourCleanup.strengthForIso(800), 0f)
        assertTrue(ColourCleanup.strengthForIso(1600) in 0.25f..0.5f)
        assertEquals(1f, ColourCleanup.strengthForIso(6400), 1e-3f)
        assertEquals(1f, ColourCleanup.strengthForIso(12800), 0f)
    }

    /**
     * Not a check: with CLEANUP_IN and CLEANUP_OUT set, runs the cleanup on a real photo stored as
     * raw integers (width, height, then ARGB pixels), so its effect can be looked at.
     */
    @Test
    fun realPhotoPreview() {
        val input = System.getenv("CLEANUP_IN") ?: return
        val (width, height, pixels) = java.io.DataInputStream(File(input).inputStream().buffered()).use { s ->
            val iw = s.readInt(); val ih = s.readInt()
            Triple(iw, ih, IntArray(iw * ih) { s.readInt() })
        }
        val t0 = System.currentTimeMillis()
        ColourCleanup.clean(pixels, width, height, (System.getenv("CLEANUP_STRENGTH") ?: "1").toFloat())
        println("cleanup ${width}x$height: ${System.currentTimeMillis() - t0} ms")
        java.io.DataOutputStream(File(System.getenv("CLEANUP_OUT")).outputStream().buffered()).use { s ->
            s.writeInt(width); s.writeInt(height); pixels.forEach { s.writeInt(it) }
        }
    }
}
