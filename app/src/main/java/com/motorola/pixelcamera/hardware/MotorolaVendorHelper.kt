package com.motorola.pixelcamera.hardware

import android.hardware.camera2.CaptureRequest
import android.util.Log

/**
 * Accessor for Motorola / Lenovo MediaTek HAL Camera2 vendor extensions.
 * Discovered on Motorola Edge 20 Fusion (kyoto).
 */
object MotorolaVendorHelper {
    private const val TAG = "MotoVendorHelper"

    // Discovered Vendor Keys from HAL dump
    const val KEY_REMOSAIC_SCENARIO = "com.lenovo.moto.remosaic.sensor.scenarioid"
    const val KEY_QUAD_PIXEL = "com.lenovo.moto.sensor.info.quad_pixel"
    const val KEY_EXTENDED_EXPOSURE = "com.lenovo.moto.sensor.info.exposureTimeRange"

    private var remosaicKey: CaptureRequest.Key<Int>? = null

    init {
        try {
            remosaicKey = CaptureRequest.Key(KEY_REMOSAIC_SCENARIO, Int::class.javaPrimitiveType ?: Int::class.java)
        } catch (e: Throwable) {
            Log.d(TAG, "Vendor key $KEY_REMOSAIC_SCENARIO not directly instantiable: ${e.message}")
        }
    }

    /**
     * Injects the 108MP remosaicing trigger scenario into a Camera2 builder if supported
     */
    fun apply108MpRemosaic(builder: CaptureRequest.Builder, enabled: Boolean) {
        val key = remosaicKey ?: return
        try {
            val scenarioId = if (enabled) 1 else 0
            builder.set(key, scenarioId)
            Log.i(TAG, "Applied Motorola 108MP remosaic scenario: $scenarioId")
        } catch (e: Exception) {
            Log.w(TAG, "Failed setting vendor remosaic key: ${e.message}")
        }
    }
}
