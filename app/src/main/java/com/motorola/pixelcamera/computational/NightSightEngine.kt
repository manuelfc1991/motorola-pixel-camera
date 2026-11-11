package com.motorola.pixelcamera.computational

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.motorola.pixelcamera.hardware.DimensityOptimizer
import kotlinx.coroutines.withContext

/**
 * Computational Night Sight Stacking Engine.
 * Aligned multi-frame merge for handheld noise reduction, then shadow brightening.
 */
object NightSightEngine {

    suspend fun processNightSight(frames: List<Bitmap>): Bitmap = withContext(DimensityOptimizer.computationDispatcher) {
        if (frames.isEmpty()) throw IllegalArgumentException("Frame list empty")
        if (frames.size == 1) return@withContext frames[0]

        val width = frames[0].width
        val height = frames[0].height

        // Handheld frames are taken hundreds of ms apart, so they are shifted by tens of pixels.
        // Use the least-shaken frame as reference, align the rest to it, and merge with
        // ghost rejection. Averaging them unaligned is what caused double/blurred images.
        val t0 = SystemClock.elapsedRealtime()
        val merged = HdrProcessor.mergeAligned(frames)
        val t2 = SystemClock.elapsedRealtime()

        // Shadow lift: same curve for every channel, so a 256-entry table replaces
        // 3 Math.pow calls per pixel (took ~15s on 12 MP).
        val lut = toneLut
        for (i in 0 until width * height) {
            val c = merged[i]
            merged[i] = (0xFF shl 24) or
                (lut[(c shr 16) and 0xFF] shl 16) or
                (lut[(c shr 8) and 0xFF] shl 8) or
                lut[c and 0xFF]
        }

        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        output.setPixels(merged, 0, width, 0, 0, width, height)
        val t3 = SystemClock.elapsedRealtime()
        Log.i(TAG, "frames=${frames.size} merge=${t2 - t0}ms tone=${t3 - t2}ms")
        return@withContext output
    }

    private const val TAG = "NightSightEngine"
    private const val BOOST = 1.25f   // gain for low-light shadow enhancement
    private const val GAMMA = 0.85f   // lifts dark areas without blowing out lamp lights

    /** Night tone curve for one 8-bit channel value. */
    internal fun tone(v: Int): Int {
        val x = (v * BOOST / 255f).coerceIn(0f, 1f)
        return (255f * Math.pow(x.toDouble(), GAMMA.toDouble()).toFloat()).toInt().coerceIn(0, 255)
    }

    internal val toneLut: IntArray by lazy { IntArray(256) { tone(it) } }
}
