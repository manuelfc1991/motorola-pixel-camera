package com.motorola.pixelcamera.computational

import android.graphics.Bitmap
import android.graphics.Matrix
import com.motorola.pixelcamera.hardware.DimensityOptimizer
import kotlinx.coroutines.withContext

/**
 * Super Resolution Crop & Detail Reconstruction Engine.
 * Extracts high-frequency details from the 108MP sensor center region.
 */
object SuperResolutionEngine {

    suspend fun process2xSuperRes(input: Bitmap): Bitmap = withContext(DimensityOptimizer.computationDispatcher) {
        val width = input.width
        val height = input.height

        // Extract center 50% region (2x optical equivalent)
        val cropX = width / 4
        val cropY = height / 4
        val cropW = width / 2
        val cropH = height / 2

        val cropped = Bitmap.createBitmap(input, cropX, cropY, cropW, cropH)

        // Upscale using high-quality filter matrix
        val matrix = Matrix().apply {
            postScale(2.0f, 2.0f)
        }

        val upscaled = Bitmap.createBitmap(cropped, 0, 0, cropW, cropH, matrix, true)

        // Run detail enhancement
        return@withContext HdrProcessor.processHdr(listOf(upscaled))
    }
}
