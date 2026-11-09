package com.motorola.pixelcamera.core

/**
 * Hardware sensor profile discovered directly via Motorola HAL 3.6 dumpsys
 * on the Motorola Edge 20 Fusion (kyoto / MT6853 Dimensity 800U).
 */
data class SensorSpecs(
    val cameraId: String,
    val sensorModel: String,
    val nominalMegapixels: Int,
    val activeWidth: Int,
    val activeHeight: Int,
    val focalLengthMm: Float,
    val aperture: Float,
    val minIso: Int,
    val maxIso: Int,
    val supportsRaw: Boolean,
    val supportsBurst: Boolean
)

enum class CameraLens(val logicalName: String) {
    REAR_MAIN("108MP Main"),
    REAR_ULTRAWIDE("8MP Ultrawide"),
    FRONT("32MP Selfie")
}

object DeviceProfile {
    const val DEVICE_MODEL = "Motorola Edge 20 Fusion"
    const val CHIPSET = "MediaTek Dimensity 800U (MT6853)"
    const val GPU = "Mali-G57 MC3"

    val REAR_MAIN = SensorSpecs(
        cameraId = "0",
        sensorModel = "Samsung ISOCELL HM2 (s5khm2)",
        nominalMegapixels = 108,
        activeWidth = 4000,
        activeHeight = 3000, // 9-in-1 Nona-binning
        focalLengthMm = 5.88f,
        aperture = 1.9f,
        minIso = 100,
        maxIso = 19200,
        supportsRaw = true,
        supportsBurst = true
    )

    val REAR_ULTRAWIDE = SensorSpecs(
        cameraId = "3",
        sensorModel = "Samsung ISOCELL 4H7 (s5k4h7)",
        nominalMegapixels = 8,
        activeWidth = 3264,
        activeHeight = 2448,
        focalLengthMm = 1.66f,
        aperture = 2.2f,
        minIso = 100,
        maxIso = 16000,
        supportsRaw = true,
        supportsBurst = true
    )

    val FRONT_SELFIE = SensorSpecs(
        cameraId = "1",
        sensorModel = "OmniVision OV32B40 (ov32b40)",
        nominalMegapixels = 32,
        activeWidth = 3264,
        activeHeight = 2448, // 4-in-1 Quad-binning
        focalLengthMm = 3.75f,
        aperture = 2.2f,
        minIso = 100,
        maxIso = 16000,
        supportsRaw = true,
        supportsBurst = true
    )

    fun getSpecs(lens: CameraLens): SensorSpecs {
        return when (lens) {
            CameraLens.REAR_MAIN -> REAR_MAIN
            CameraLens.REAR_ULTRAWIDE -> REAR_ULTRAWIDE
            CameraLens.FRONT -> FRONT_SELFIE
        }
    }
}
