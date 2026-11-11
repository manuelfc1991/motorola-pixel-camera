package com.motorola.pixelcamera.computational

import java.util.stream.IntStream
import kotlin.math.exp

/**
 * Removes the colour blotches a high-ISO merge leaves behind.
 *
 * After several frames are averaged the fine grain is gone, but slow drifts of colour remain:
 * patches tens of pixels wide that lean pink, green or grey. Brightness detail is not touched.
 * Colour is smoothed on a quarter-size grid, never across a brightness or colour edge, and only
 * the change is carried back to the full image, so fine coloured detail keeps its place.
 */
object ColourCleanup {

    private const val CELL = 4
    private const val RADIUS = 4
    private const val PASSES = 1

    /** exp(-t) for t in 0 until [EXP_RANGE], so the inner loop needs no call to exp. */
    private const val EXP_RANGE = 8f
    private const val EXP_STEPS = 512
    private val expTable = FloatArray(EXP_STEPS + 1) { exp(-it * EXP_RANGE / EXP_STEPS) }

    /** Brightness difference (0..255) at which a neighbour stops counting as the same surface. */
    private const val LUMA_SIGMA = 10f

    /** Colour difference a neighbour may have and still be averaged in, at strength 0 and 1. */
    private const val CHROMA_SIGMA_MIN = 3f
    private const val CHROMA_SIGMA_MAX = 12f

    /** ISO at which the cleanup starts, and the ISO from which it runs at full strength. */
    private const val ISO_START = 800f
    private const val ISO_FULL = 6400f

    /** 0 below [ISO_START], rising to 1 at [ISO_FULL]. */
    fun strengthForIso(iso: Int): Float {
        if (iso <= ISO_START) return 0f
        val stops = Math.log((iso / ISO_START).toDouble()) / Math.log((ISO_FULL / ISO_START).toDouble())
        return stops.toFloat().coerceIn(0.25f, 1f)
    }

    /** Cleans [pixels] (ARGB, [width] x [height]) in place. */
    fun clean(pixels: IntArray, width: Int, height: Int, strength: Float) {
        if (strength <= 0f || width < CELL * 4 || height < CELL * 4) return
        val sw = width / CELL
        val sh = height / CELL
        val size = sw * sh
        val luma = FloatArray(size)
        val cb = FloatArray(size)
        val cr = FloatArray(size)
        parallelRows(sh) { sy ->
            for (sx in 0 until sw) {
                var r = 0
                var g = 0
                var b = 0
                for (y in sy * CELL until sy * CELL + CELL) {
                    var i = y * width + sx * CELL
                    repeat(CELL) {
                        val p = pixels[i++]
                        r += (p shr 16) and 0xFF
                        g += (p shr 8) and 0xFF
                        b += p and 0xFF
                    }
                }
                val n = (CELL * CELL).toFloat()
                val rf = r / n
                val gf = g / n
                val bf = b / n
                val yv = 0.299f * rf + 0.587f * gf + 0.114f * bf
                val i = sy * sw + sx
                luma[i] = yv
                cb[i] = bf - yv
                cr[i] = rf - yv
            }
        }

        var smoothCb = cb
        var smoothCr = cr
        repeat(PASSES) {
            val nextCb = FloatArray(size)
            val nextCr = FloatArray(size)
            smooth(luma, smoothCb, smoothCr, nextCb, nextCr, sw, sh, strength)
            smoothCb = nextCb
            smoothCr = nextCr
        }
        val dCb = FloatArray(size) { smoothCb[it] - cb[it] }
        val dCr = FloatArray(size) { smoothCr[it] - cr[it] }

        // Carry the change back. Cb = B - Y and Cr = R - Y, so with Y fixed:
        // R moves by dCr, B by dCb, and G by whatever keeps Y where it was.
        parallelRows(height) { y ->
            val fy = ((y + 0.5f) / CELL - 0.5f).coerceIn(0f, sh - 1f)
            val y0 = fy.toInt().coerceAtMost(sh - 2)
            val wy = fy - y0
            for (x in 0 until width) {
                val fx = ((x + 0.5f) / CELL - 0.5f).coerceIn(0f, sw - 1f)
                val x0 = fx.toInt().coerceAtMost(sw - 2)
                val wx = fx - x0
                val i00 = y0 * sw + x0
                val w00 = (1 - wx) * (1 - wy)
                val w10 = wx * (1 - wy)
                val w01 = (1 - wx) * wy
                val w11 = wx * wy
                val db = dCb[i00] * w00 + dCb[i00 + 1] * w10 + dCb[i00 + sw] * w01 + dCb[i00 + sw + 1] * w11
                val dr = dCr[i00] * w00 + dCr[i00 + 1] * w10 + dCr[i00 + sw] * w01 + dCr[i00 + sw + 1] * w11
                val dg = -(0.299f * dr + 0.114f * db) / 0.587f
                val p = pixels[y * width + x]
                val r = (((p shr 16) and 0xFF) + dr + 0.5f).toInt().coerceIn(0, 255)
                val g = (((p shr 8) and 0xFF) + dg + 0.5f).toInt().coerceIn(0, 255)
                val b = ((p and 0xFF) + db + 0.5f).toInt().coerceIn(0, 255)
                pixels[y * width + x] = (p and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
            }
        }
    }

    /** One edge-stopping pass: each cell takes the colour of neighbours on the same surface. */
    private fun smooth(
        luma: FloatArray, cb: FloatArray, cr: FloatArray, outCb: FloatArray, outCr: FloatArray,
        sw: Int, sh: Int, strength: Float
    ) {
        val chromaSigma = CHROMA_SIGMA_MIN + (CHROMA_SIGMA_MAX - CHROMA_SIGMA_MIN) * strength
        val toStep = EXP_STEPS / EXP_RANGE
        val lumaScale = toStep / (2f * LUMA_SIGMA * LUMA_SIGMA)
        val chromaScale = toStep / (2f * chromaSigma * chromaSigma)
        parallelRows(sh) { y ->
            for (x in 0 until sw) {
                val i = y * sw + x
                val l = luma[i]
                val b = cb[i]
                val r = cr[i]
                var sumB = 0f
                var sumR = 0f
                var sumW = 0f
                for (ny in (y - RADIUS).coerceAtLeast(0)..(y + RADIUS).coerceAtMost(sh - 1)) {
                    var j = ny * sw + (x - RADIUS).coerceAtLeast(0)
                    val end = ny * sw + (x + RADIUS).coerceAtMost(sw - 1)
                    while (j <= end) {
                        val dl = luma[j] - l
                        val db = cb[j] - b
                        val dr = cr[j] - r
                        val step = (dl * dl * lumaScale + (db * db + dr * dr) * chromaScale).toInt()
                        if (step < EXP_STEPS) {
                            val w = expTable[step]
                            sumB += cb[j] * w
                            sumR += cr[j] * w
                            sumW += w
                        }
                        j++
                    }
                }
                outCb[i] = sumB / sumW
                outCr[i] = sumR / sumW
            }
        }
    }

    private fun parallelRows(rows: Int, body: (Int) -> Unit) =
        IntStream.range(0, rows).parallel().forEach { body(it) }
}
