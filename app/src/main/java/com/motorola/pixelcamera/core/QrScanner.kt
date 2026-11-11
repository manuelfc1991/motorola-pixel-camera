package com.motorola.pixelcamera.core

import android.os.SystemClock
import androidx.camera.core.ImageProxy
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer

/** Looks for a QR code in RGBA analysis frames, a few times a second. */
class QrScanner {
    private val reader = MultiFormatReader().apply {
        setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
    }
    private var lastScanMs = 0L
    private var luma = ByteArray(0)

    /** Text of the QR code in [image], or null. Frames arriving sooner than [SCAN_INTERVAL_MS] are skipped. */
    fun scan(image: ImageProxy): String? {
        val now = SystemClock.elapsedRealtime()
        if (now - lastScanMs < SCAN_INTERVAL_MS) return null
        lastScanMs = now

        val plane = image.planes[0]
        val buffer = plane.buffer
        val w = image.width
        val h = image.height
        if (luma.size != w * h) luma = ByteArray(w * h)
        // Green is close enough to luminance for a black-and-white code
        for (y in 0 until h) {
            var src = y * plane.rowStride + 1
            val row = y * w
            for (x in 0 until w) {
                luma[row + x] = buffer.get(src)
                src += plane.pixelStride
            }
        }
        return decode(luma, w, h)
    }

    /** Text of the QR code in a [w]x[h] luminance image, or null. */
    internal fun decode(luma: ByteArray, w: Int, h: Int): String? {
        val bitmap = BinaryBitmap(HybridBinarizer(PlanarYUVLuminanceSource(luma, w, h, 0, 0, w, h, false)))
        return try {
            reader.decodeWithState(bitmap).text
        } catch (e: NotFoundException) {
            null
        } finally {
            reader.reset()
        }
    }

    private companion object {
        const val SCAN_INTERVAL_MS = 400L
    }
}
