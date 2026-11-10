package com.motorola.pixelcamera.core

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.hardware.camera2.CaptureRequest
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.video.VideoCapture
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.motorola.pixelcamera.computational.AstrophotographyEngine
import com.motorola.pixelcamera.computational.HdrProcessor
import com.motorola.pixelcamera.computational.MotionPhotoEngine
import com.motorola.pixelcamera.computational.NightSightEngine
import com.motorola.pixelcamera.computational.PanoramaEngine
import com.motorola.pixelcamera.computational.PortraitEngine
import com.motorola.pixelcamera.hardware.LocationHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class CameraManager(private val context: Context) {

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var activeRecording: Recording? = null

    val panoramaEngine by lazy { PanoramaEngine(context) }
    val audioZoomManager by lazy { AudioZoomManager(context) }
    val locationHelper by lazy { LocationHelper(context) }

    enum class TopShotMode {
        OFF, AUTO, ON
    }

    var topShotMode: TopShotMode = TopShotMode.AUTO
    var audioZoomEnabled: Boolean = true
        set(value) {
            field = value
            audioZoomManager.setAudioZoomEnabled(value)
        }

    // Physical Camera IDs discovered on Motorola Edge 20 Fusion (kyoto)
    var currentCameraId: String = CAMERA_MAIN
        private set

    var currentZoomRatio: Float = 1.0f
        private set

    var currentCameraMode: CameraMode = CameraMode.CAMERA

    enum class VideoResolution(val label: String, val quality: Quality) {
        FHD("1080p", Quality.FHD),
        UHD("4K", Quality.UHD)
    }

    enum class VideoFps(val label: String, val fps: Int) {
        FPS_30("30 fps", 30),
        FPS_60("60 fps", 60)
    }

    var currentVideoResolution: VideoResolution = VideoResolution.FHD
    var currentVideoFps: VideoFps = VideoFps.FPS_30

    /** Played on photo capture and video start/stop when enabled in Settings. */
    var shutterSoundEnabled: Boolean = true
    var videoStabilizationEnabled: Boolean = true
    private val actionSound by lazy { android.media.MediaActionSound() }

    private fun playSound(sound: Int) {
        if (!shutterSoundEnabled) return
        runCatching { actionSound.play(sound) }
    }
    var isAstrophotographyActive: Boolean = false
    var aspectRatio: Int = AspectRatio.RATIO_4_3
    var isGoogleAwbEnabled: Boolean = false
    var hdrPlusMode: Int = 0 // 0: Off (direct ISP JPEG), 1: On (2-frame merge), 2: Enhanced (3-frame merge)

    var onHistogramUpdate: ((IntArray, IntArray, IntArray) -> Unit)? = null

    var flashMode: Int = ImageCapture.FLASH_MODE_OFF
        set(value) {
            field = value
            imageCapture?.flashMode = value
        }

    private var currentLifecycleOwner: LifecycleOwner? = null
    private var currentPreviewView: PreviewView? = null

    suspend fun initialize(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        currentLifecycleOwner = lifecycleOwner
        currentPreviewView = previewView
        val provider = getCameraProvider()
        cameraProvider = provider
        bindCameraUseCases(lifecycleOwner, previewView)
    }

    private suspend fun getCameraProvider(): ProcessCameraProvider =
        suspendCancellableCoroutine { continuation ->
            val providerFuture = ProcessCameraProvider.getInstance(context)
            providerFuture.addListener({
                try {
                    continuation.resume(providerFuture.get())
                } catch (e: Exception) {
                    continuation.resumeWithException(e)
                }
            }, ContextCompat.getMainExecutor(context))
        }

    @OptIn(ExperimentalCamera2Interop::class)
    fun bindCameraUseCases(
        lifecycleOwner: LifecycleOwner? = null,
        previewView: PreviewView? = null
    ) {
        val owner = lifecycleOwner ?: currentLifecycleOwner ?: return
        val view = previewView ?: currentPreviewView ?: return
        currentLifecycleOwner = owner
        currentPreviewView = view

        val provider = cameraProvider ?: return
        provider.availableCameraInfos.forEach {
            try {
                val c2 = Camera2CameraInfo.from(it)
                Log.i(TAG, "Available CameraX camera id=${c2.cameraId}, facing=${it.lensFacing}")
            } catch (e: Exception) {
                Log.w(TAG, "CameraInfo error: ${e.message}")
            }
        }
        provider.unbindAll()

        if (currentCameraId == CAMERA_ULTRAWIDE && !isUltrawideAvailable()) {
            Log.w(TAG, "Ultrawide camera is not exposed to this app; using main camera")
            currentCameraId = CAMERA_MAIN
        }
        val cameraSelector = getCameraSelectorForId(currentCameraId)

        val aspectStrategy = if (aspectRatio == AspectRatio.RATIO_16_9 || currentCameraMode == CameraMode.VIDEO) {
            AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
        } else {
            AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
        }

        // 1. Preview Use Case with Dynamic Aspect Ratio (4:3 or 16:9)
        val previewResolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(aspectStrategy)
            .setResolutionStrategy(
                ResolutionStrategy(
                    if (aspectRatio == AspectRatio.RATIO_16_9 || currentCameraMode == CameraMode.VIDEO) Size(1920, 1080) else Size(1920, 1440),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                )
            )
            .build()

        val previewBuilder = Preview.Builder()
            .setResolutionSelector(previewResolutionSelector)

        val previewExtender = Camera2Interop.Extender(previewBuilder)
        previewExtender.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        previewExtender.setCaptureRequestOption(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO)
        if (isGoogleAwbEnabled) {
            previewExtender.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
        }
        if (currentCameraMode == CameraMode.NIGHT_SIGHT) {
            previewExtender.setCaptureRequestOption(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_USE_SCENE_MODE)
            previewExtender.setCaptureRequestOption(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_NIGHT)
        }
        if (currentCameraMode == CameraMode.VIDEO) {
            val targetFps = if (currentVideoFps == VideoFps.FPS_60 && currentVideoResolution != VideoResolution.UHD) {
                android.util.Range(60, 60)
            } else {
                android.util.Range(30, 30)
            }
            previewExtender.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, targetFps)
        }

        preview = previewBuilder.build().also {
            it.setSurfaceProvider(view.surfaceProvider)
        }

        // 2. Image Capture Use Case (Maximum Quality & Hardware ISP Clarity Tuning)
        val captureResolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(aspectStrategy)
            .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
            .build()

        val captureBuilder = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setResolutionSelector(captureResolutionSelector)
            .setFlashMode(flashMode)
            .setJpegQuality(100)

        // Inject Camera2 hardware ISP parameters for razor-sharp clarity directly from MediaTek ISP
        val camera2Extender = Camera2Interop.Extender(captureBuilder)
        camera2Extender.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        camera2Extender.setCaptureRequestOption(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO)
        camera2Extender.setCaptureRequestOption(CaptureRequest.SHADING_MODE, CaptureRequest.SHADING_MODE_HIGH_QUALITY)
        camera2Extender.setCaptureRequestOption(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_HIGH_QUALITY)
        camera2Extender.setCaptureRequestOption(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
        camera2Extender.setCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
        camera2Extender.setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY)
        camera2Extender.setCaptureRequestOption(CaptureRequest.HOT_PIXEL_MODE, CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY)
        camera2Extender.setCaptureRequestOption(CaptureRequest.JPEG_QUALITY, 100.toByte())

        if (currentCameraMode == CameraMode.NIGHT_SIGHT) {
            camera2Extender.setCaptureRequestOption(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_USE_SCENE_MODE)
            camera2Extender.setCaptureRequestOption(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_NIGHT)
        }

        imageCapture = captureBuilder.build()

        // 3. Video Capture Use Case (Full HD 1080p / 4K recording)
        val targetQuality = if (currentVideoResolution == VideoResolution.UHD && currentCameraId != CAMERA_FRONT) {
            Quality.UHD
        } else {
            Quality.FHD
        }
        val recorder = Recorder.Builder()
            .setQualitySelector(
                QualitySelector.from(
                    targetQuality,
                    FallbackStrategy.lowerQualityOrHigherThan(Quality.FHD)
                )
            )
            .build()
        val wants60 = currentVideoFps == VideoFps.FPS_60 && targetQuality != Quality.UHD && currentCameraId != CAMERA_FRONT
        val videoBuilder = VideoCapture.Builder(recorder)
            .setTargetFrameRate(if (wants60) android.util.Range(60, 60) else android.util.Range(30, 30))
        // CameraX 1.3 has no stabilization switch on VideoCapture, so request the HAL's EIS directly.
        // The HAL only keeps full frame rate with EIS at 1080p30; at 4K it halves the frame rate
        // and at 60 fps the recording produces no frames.
        val eis = videoStabilizationEnabled && currentCameraMode == CameraMode.VIDEO &&
            targetQuality == Quality.FHD && !wants60
        Camera2Interop.Extender(videoBuilder).setCaptureRequestOption(
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
            if (eis) CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON else CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
        )
        Log.i(TAG, "Video config: quality=$targetQuality fps=${if (wants60) 60 else 30} eis=$eis")
        videoCapture = videoBuilder.build()

        // 4. Image Analysis Use Case (Real-time Live RGB/Luminance Histogram, Top Shot, Panorama)
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()

        analysis.setAnalyzer(cameraExecutor) { imageProxy ->
            try {
                if (currentCameraMode == CameraMode.PRO && onHistogramUpdate != null) {
                    val plane = imageProxy.planes[0]
                    val buffer = plane.buffer
                    val remaining = buffer.remaining()
                    if (remaining > 0) {
                        val step = (remaining / 2048).coerceAtLeast(4)
                        val histR = IntArray(256)
                        val histG = IntArray(256)
                        val histB = IntArray(256)
                        var p = 0
                        while (p < remaining - 3) {
                            val r = buffer.get(p).toInt() and 0xFF
                            val g = buffer.get(p + 1).toInt() and 0xFF
                            val b = buffer.get(p + 2).toInt() and 0xFF
                            histR[r]++
                            histG[g]++
                            histB[b]++
                            p += step
                        }
                        onHistogramUpdate?.invoke(histR, histG, histB)
                    }
                }

                // If Top Shot or Panorama active, stream frames
                val needsFrame = (topShotMode != TopShotMode.OFF && (currentCameraMode == CameraMode.CAMERA || currentCameraMode == CameraMode.PORTRAIT)) ||
                        (currentCameraMode == CameraMode.PANORAMA && panoramaEngine.isCapturing())

                if (needsFrame) {
                    val bmp = imageProxyRgbaToBitmap(imageProxy)
                    if (bmp != null) {
                        if (topShotMode != TopShotMode.OFF) {
                            MotionPhotoEngine.pushPreviewFrame(bmp)
                        }
                        if (currentCameraMode == CameraMode.PANORAMA && panoramaEngine.isCapturing()) {
                            panoramaEngine.processIncomingFrame(bmp)
                        }
                    }
                }
            } catch (e: Exception) {
                // Ignore transient analysis error
            } finally {
                imageProxy.close()
            }
        }
        imageAnalysis = analysis

        // Bind use cases based on active mode to ensure maximum hardware stability
        val useCases = mutableListOf<UseCase>()
        useCases.add(preview!!)

        if (currentCameraMode == CameraMode.VIDEO) {
            useCases.add(videoCapture!!)
            useCases.add(imageCapture!!)
        } else {
            useCases.add(imageCapture!!)
            useCases.add(imageAnalysis!!)
        }

        try {
            camera = provider.bindToLifecycle(
                owner,
                cameraSelector,
                *useCases.toTypedArray()
            )
            val zState = camera?.cameraInfo?.zoomState?.value
            Log.i(TAG, "Active camera zoom range: min=${zState?.minZoomRatio}, max=${zState?.maxZoomRatio}, current=${zState?.zoomRatio}")
            setZoom(currentZoomRatio)
            when (currentCameraMode) {
                CameraMode.VIDEO -> applyVideoFps()
                CameraMode.PRO -> applyManualControls()
                else -> clearManualControls()
            }
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed, falling back to minimal", exc)
            try {
                // Fallback to preview + capture
                camera = provider.bindToLifecycle(
                    owner,
                    cameraSelector,
                    preview!!,
                    imageCapture!!
                )
                setZoom(currentZoomRatio)
            } catch (e: Exception) {
                Log.e(TAG, "Fatal camera binding failure", e)
            }
        }
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun getCameraSelectorForId(targetId: String): CameraSelector {
        if (targetId == CAMERA_FRONT) return CameraSelector.DEFAULT_FRONT_CAMERA
        // Pick the exact rear camera by Camera2 id; fall back to the default rear camera.
        return CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .addCameraFilter { infos ->
                val exact = infos.filter { runCatching { Camera2CameraInfo.from(it).cameraId }.getOrNull() == targetId }
                exact.ifEmpty {
                    infos.filter { runCatching { Camera2CameraInfo.from(it).cameraId }.getOrNull() == CAMERA_MAIN }.ifEmpty { infos }
                }
            }
            .build()
    }

    /**
     * True only when the ultrawide (camera id 3) is exposed to this app. On this phone the
     * vendor framework hides auxiliary cameras from apps outside vendor.camera.aux.packagelist.
     */
    @OptIn(ExperimentalCamera2Interop::class)
    fun isUltrawideAvailable(): Boolean {
        val provider = cameraProvider ?: return false
        return provider.availableCameraInfos.any {
            runCatching { Camera2CameraInfo.from(it).cameraId }.getOrNull() == CAMERA_ULTRAWIDE
        }
    }

    /**
     * Switch camera lens: .6x Ultrawide (Camera 3), 1x Main (Camera 0), 2x Tele (Camera 0), Front (Camera 1)
     */
    fun switchToLens(targetCameraId: String, zoomRatio: Float) {
        val owner = currentLifecycleOwner ?: return
        val view = currentPreviewView ?: return

        currentZoomRatio = zoomRatio

        if (currentCameraId == targetCameraId) {
            // Same physical lens: adjust zoom smoothly without rebinding
            setZoom(zoomRatio)
        } else {
            // Switch physical camera lens
            currentCameraId = targetCameraId
            bindCameraUseCases(owner, view)
        }
    }

    fun switchCamera() {
        val nextId = if (currentCameraId == CAMERA_FRONT) CAMERA_MAIN else CAMERA_FRONT
        switchToLens(nextId, 1.0f)
    }

    fun isFrontCamera(): Boolean = currentCameraId == CAMERA_FRONT

    fun setZoom(ratio: Float) {
        currentZoomRatio = ratio
        val zState = camera?.cameraInfo?.zoomState?.value
        val minRatio = zState?.minZoomRatio ?: 1.0f
        val maxRatio = zState?.maxZoomRatio ?: 8.0f
        val clamped = ratio.coerceIn(minRatio, maxRatio)
        camera?.cameraControl?.setZoomRatio(clamped)
        audioZoomManager.onZoomRatioChanged(clamped)
    }

    fun maxZoomRatio(): Float = camera?.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 8.0f

    fun setExposureCompensation(evIndex: Int) {
        val range = getExposureCompensationRange()
        val clamped = evIndex.coerceIn(range.lower, range.upper)
        camera?.cameraControl?.setExposureCompensationIndex(clamped)
    }

    fun getExposureCompensationRange(): android.util.Range<Int> {
        return camera?.cameraInfo?.exposureState?.exposureCompensationRange ?: android.util.Range(-6, 6)
    }

    fun getExposureCompensationStep(): Float {
        val step = camera?.cameraInfo?.exposureState?.exposureCompensationStep
        return if (step != null && step.denominator != 0) {
            step.numerator.toFloat() / step.denominator.toFloat()
        } else {
            0.5f
        }
    }

    fun getExposureCompensationIndex(): Int {
        return camera?.cameraInfo?.exposureState?.exposureCompensationIndex ?: 0
    }

    @OptIn(ExperimentalCamera2Interop::class)
    fun applyVideoFps() {
        val cam = camera ?: return
        val c2Control = Camera2CameraControl.from(cam.cameraControl)
        val targetFps = if (currentVideoFps == VideoFps.FPS_60 && currentVideoResolution != VideoResolution.UHD) {
            android.util.Range(60, 60)
        } else {
            android.util.Range(30, 30)
        }
        val options = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, targetFps)
            .build()
        c2Control.setCaptureRequestOptions(options)
        Log.i(TAG, "Applied video FPS range: $targetFps (Res: $currentVideoResolution, FPS: $currentVideoFps)")
    }

    fun setVideoProfile(resolution: VideoResolution, fps: VideoFps) {
        val resolutionChanged = (currentVideoResolution != resolution)
        val fpsChanged = (currentVideoFps != fps)

        currentVideoResolution = resolution
        currentVideoFps = if (resolution == VideoResolution.UHD) VideoFps.FPS_30 else fps

        if (currentCameraMode == CameraMode.VIDEO) {
            // Frame rate is part of the recorder configuration, so both changes need a rebind.
            if (resolutionChanged || fpsChanged) {
                bindCameraUseCases()
            }
        }
    }

    fun setVideoResolution(resolution: VideoResolution) {
        setVideoProfile(resolution, currentVideoFps)
    }

    fun setVideoFps(fps: VideoFps) {
        setVideoProfile(currentVideoResolution, fps)
    }

    var currentManualIso: Int = 0 // 0 = Auto
    var currentManualExposureTimeNs: Long = 0L // 0 = Auto
    var currentManualFocusDistance: Float = -1f // -1 = Auto AF
    var currentManualAwbMode: Int = CaptureRequest.CONTROL_AWB_MODE_AUTO

    @OptIn(ExperimentalCamera2Interop::class)
    fun setManualIso(iso: Int) {
        currentManualIso = iso
        applyManualControls()
    }

    @OptIn(ExperimentalCamera2Interop::class)
    fun setManualShutter(ns: Long) {
        currentManualExposureTimeNs = ns
        applyManualControls()
    }

    @OptIn(ExperimentalCamera2Interop::class)
    fun setManualFocus(distance: Float) {
        currentManualFocusDistance = distance
        applyManualControls()
    }

    @OptIn(ExperimentalCamera2Interop::class)
    fun setManualWb(mode: Int) {
        currentManualAwbMode = mode
        applyManualControls()
    }

    @OptIn(ExperimentalCamera2Interop::class)
    fun applyManualControls() {
        val cam = camera ?: return
        val c2Control = Camera2CameraControl.from(cam.cameraControl)
        val optionsBuilder = CaptureRequestOptions.Builder()

        // 1. Exposure (ISO + Shutter)
        if (currentManualIso > 0 || currentManualExposureTimeNs > 0L) {
            optionsBuilder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            if (currentManualIso > 0) {
                optionsBuilder.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, currentManualIso)
            }
            if (currentManualExposureTimeNs > 0L) {
                optionsBuilder.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, currentManualExposureTimeNs)
            } else {
                optionsBuilder.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, 16_666_666L)
            }
        } else {
            optionsBuilder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        }

        // 2. Focus Distance
        if (currentManualFocusDistance >= 0f) {
            optionsBuilder.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            optionsBuilder.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, currentManualFocusDistance)
        } else {
            optionsBuilder.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        }

        // 3. White Balance
        optionsBuilder.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, currentManualAwbMode)

        c2Control.setCaptureRequestOptions(optionsBuilder.build())
    }

    /** Drops every Pro override so the other modes run on full auto. */
    @OptIn(ExperimentalCamera2Interop::class)
    fun clearManualControls() {
        val cam = camera ?: return
        runCatching { Camera2CameraControl.from(cam.cameraControl).clearCaptureRequestOptions() }
    }

    fun resetManualState() {
        currentManualIso = 0
        currentManualExposureTimeNs = 0L
        currentManualFocusDistance = -1f
        currentManualAwbMode = CaptureRequest.CONTROL_AWB_MODE_AUTO
    }

    fun hasFlashUnit(): Boolean {
        return camera?.cameraInfo?.hasFlashUnit() ?: false
    }

    fun setTorchEnabled(enabled: Boolean) {
        if (hasFlashUnit()) {
            camera?.cameraControl?.enableTorch(enabled)
        }
    }

    fun focusAt(meteringPoint: MeteringPoint) {
        val action = FocusMeteringAction.Builder(meteringPoint, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(3, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        camera?.cameraControl?.startFocusAndMetering(action)
    }

    /**
     * Executes photo capture with maximum clarity and appropriate computational processing
     */
    suspend fun capturePhoto(
        mode: CameraMode,
        portraitBlurStrength: Float = 14f,
        onStatusUpdate: (String) -> Unit
    ): Uri? = withContext(Dispatchers.IO) {
        val capture = imageCapture ?: return@withContext null
        if (mode != CameraMode.PANORAMA) playSound(android.media.MediaActionSound.SHUTTER_CLICK)

        when (mode) {
            CameraMode.PRO -> {
                // Manual exposure: keep the single hardware frame exactly as shot.
                onStatusUpdate("Capturing manual frame...")
                takePictureDirect(capture)
            }
            CameraMode.CAMERA -> {
                val shouldRecordMotion = (topShotMode != TopShotMode.OFF) && (mode == CameraMode.CAMERA)
                val motionFrames = if (shouldRecordMotion) MotionPhotoEngine.snapshotFrames() else emptyList()

                val savedUri = if (hdrPlusMode == 0 && !isGoogleAwbEnabled) {
                    onStatusUpdate("Capturing...")
                    takePictureDirect(capture)
                } else {
                    val frameCount = if (hdrPlusMode == 2) 3 else if (hdrPlusMode == 1) 2 else 1
                    onStatusUpdate("HDR+ burst: Capturing $frameCount frames...")
                    val burst = mutableListOf<Bitmap>()
                    for (i in 0 until frameCount) {
                        val bmp = runCatching { takeSingleBitmap(capture) }.getOrNull()
                        if (bmp != null) burst.add(bmp)
                    }

                    if (burst.isNotEmpty()) {
                        onStatusUpdate("Merging HDR+ & Pixel tone mapping...")
                        val processed = HdrProcessor.processHdr(burst, enableGoogleAwb = isGoogleAwbEnabled)
                        onStatusUpdate("Saving HDR+ photo...")
                        saveBitmapToMediaStore(processed)
                    } else {
                        takePictureDirect(capture)
                    }
                }

                if (shouldRecordMotion && savedUri != null && motionFrames.size >= 4) {
                    try {
                        onStatusUpdate("Embedding Motion Photo...")
                        val tempMp4 = File(context.cacheDir, "motion_${System.currentTimeMillis()}.mp4")
                        if (MotionPhotoEngine.encodeFramesToMp4(motionFrames, tempMp4)) {
                            MotionPhotoEngine.attachMotionPhoto(context, savedUri, tempMp4)
                            tempMp4.delete()
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Motion Photo attach warning: ${e.message}")
                    }
                }
                savedUri
            }
            CameraMode.PORTRAIT -> {
                val shouldRecordMotion = (topShotMode != TopShotMode.OFF)
                val motionFrames = if (shouldRecordMotion) MotionPhotoEngine.snapshotFrames() else emptyList()

                onStatusUpdate("Capturing portrait frame...")
                val baseBitmap = runCatching { takeSingleBitmap(capture) }.getOrNull() ?: return@withContext null
                onStatusUpdate("Computing progressive bokeh...")
                val portraitBitmap = PortraitEngine.applyPortraitBokeh(
                    baseBitmap,
                    focusX = 0.5f,
                    focusY = 0.45f,
                    blurIntensity = portraitBlurStrength
                )
                onStatusUpdate("Saving Portrait JPEG...")
                val savedUri = saveBitmapToMediaStore(portraitBitmap)

                if (shouldRecordMotion && savedUri != null && motionFrames.size >= 4) {
                    try {
                        onStatusUpdate("Embedding Motion Photo...")
                        val tempMp4 = File(context.cacheDir, "motion_${System.currentTimeMillis()}.mp4")
                        if (MotionPhotoEngine.encodeFramesToMp4(motionFrames, tempMp4)) {
                            MotionPhotoEngine.attachMotionPhoto(context, savedUri, tempMp4)
                            tempMp4.delete()
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Motion Photo attach warning: ${e.message}")
                    }
                }
                savedUri
            }
            CameraMode.NIGHT_SIGHT -> {
                if (isAstrophotographyActive) {
                    onStatusUpdate("Astrophotography: Capturing 8 long exposures...")
                    val burst = mutableListOf<Bitmap>()
                    for (i in 0 until 8) {
                        val bmp = runCatching { takeSingleBitmap(capture) }.getOrNull()
                        if (bmp != null) burst.add(bmp)
                    }
                    if (burst.isNotEmpty()) {
                        onStatusUpdate("Aligning stars & sidereal stacking...")
                        val processed = AstrophotographyEngine.processAstroStack(burst)
                        onStatusUpdate("Saving Astrophotography photo...")
                        saveBitmapToMediaStore(processed)
                    } else {
                        takePictureDirect(capture)
                    }
                } else {
                    // Each capture is already a 6-frame aligned MediaTek MFNR merge (~1.5s in the HAL).
                    // Handheld, more captures only add hold time and hand drift, so keep it short.
                    onStatusUpdate("Night Sight: Capturing $NIGHT_HANDHELD_CAPTURES multi-frame exposures...")
                    val burst = mutableListOf<Bitmap>()
                    for (i in 0 until NIGHT_HANDHELD_CAPTURES) {
                        val tc = android.os.SystemClock.elapsedRealtime()
                        val bmp = runCatching { takeSingleBitmap(capture) }.getOrNull()
                        Log.i(TAG, "Night Sight capture+decode ${i + 1}: ${android.os.SystemClock.elapsedRealtime() - tc}ms")
                        if (bmp != null) burst.add(bmp)
                    }
                    if (burst.isNotEmpty()) {
                        onStatusUpdate("Fusing low-light frames & de-noising...")
                        val processed = NightSightEngine.processNightSight(burst)
                        onStatusUpdate("Saving Night Sight photo...")
                        val ts = android.os.SystemClock.elapsedRealtime()
                        saveBitmapToMediaStore(processed).also {
                            Log.i(TAG, "Night Sight save=${android.os.SystemClock.elapsedRealtime() - ts}ms")
                        }
                    } else {
                        takePictureDirect(capture)
                    }
                }
            }
            CameraMode.LONG_EXPOSURE -> {
                onStatusUpdate("Long Exposure: Capturing 12 motion frames...")
                val burst = mutableListOf<Bitmap>()
                for (i in 0 until 12) {
                    val bmp = runCatching { takeSingleBitmap(capture) }.getOrNull()
                    if (bmp != null) burst.add(bmp)
                }
                if (burst.isNotEmpty()) {
                    onStatusUpdate("Blending motion blur & light trails...")
                    val processed = HdrProcessor.blendLongExposure(burst)
                    onStatusUpdate("Saving Long Exposure photo...")
                    saveBitmapToMediaStore(processed)
                } else {
                    takePictureDirect(capture)
                }
            }
            CameraMode.PANORAMA -> {
                if (panoramaEngine.isCapturing()) {
                    onStatusUpdate("Stitching cylindrical panorama...")
                    panoramaEngine.finishCapture(locationHelper.getCurrentLocation())
                } else {
                    onStatusUpdate("Aligning panorama horizon...")
                    panoramaEngine.startCapture()
                    null
                }
            }
            CameraMode.VIDEO -> {
                // Video is handled by startRecording / stopRecording
                takePictureDirect(capture)
            }
        }
    }

    fun startPanoramaCapture() {
        panoramaEngine.startCapture()
    }

    suspend fun stopPanoramaCapture(): Uri? {
        return panoramaEngine.finishCapture(locationHelper.getCurrentLocation())
    }

    fun isCapturingPanorama(): Boolean = panoramaEngine.isCapturing()

    /**
     * Direct CameraX output into MediaStore: Preserves hardware ISP demosaicing,
     * hardware edge enhancement, and all EXIF tags without lossy software recompression.
     */
    private suspend fun takePictureDirect(capture: ImageCapture): Uri? =
        suspendCancellableCoroutine { cont ->
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val name = "IMG_${timeStamp}_PXL"
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.jpg")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/Camera")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }

            val gpsLocation = locationHelper.getCurrentLocation()
            val outputOptions = ImageCapture.OutputFileOptions.Builder(
                context.contentResolver,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues
            ).apply {
                if (gpsLocation != null) {
                    setMetadata(ImageCapture.Metadata().apply { location = gpsLocation })
                }
            }.build()

            capture.takePicture(
                outputOptions,
                cameraExecutor,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        val uri = outputFileResults.savedUri
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && uri != null) {
                            val values = ContentValues().apply {
                                put(MediaStore.Images.Media.IS_PENDING, 0)
                            }
                            try {
                                context.contentResolver.update(uri, values, null, null)
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to clear pending flag: ${e.message}")
                            }
                        }
                        Log.i(TAG, "Direct ISP capture saved: $uri")
                        cont.resume(uri)
                    }

                    override fun onError(exception: ImageCaptureException) {
                        Log.e(TAG, "Direct capture failed: ${exception.message}", exception)
                        cont.resume(null)
                    }
                }
            )
        }

    /**
     * Start high-definition Video Recording directly to DCIM/Camera
     */
    fun startVideoRecording(
        onVideoRecordEvent: (VideoRecordEvent) -> Unit
    ): Boolean {
        val video = videoCapture ?: return false
        if (activeRecording != null) return false

        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val name = "VID_${timeStamp}_PXL"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.mp4")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/Camera")
            }
        }

        val gpsLocation = locationHelper.getCurrentLocation()
        val mediaStoreOutput = MediaStoreOutputOptions.Builder(
            context.contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(contentValues)
            .apply {
                if (gpsLocation != null) {
                    setLocation(gpsLocation)
                }
            }.build()

        val pendingRecording = video.output.prepareRecording(context, mediaStoreOutput)

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            pendingRecording.withAudioEnabled()
        }

        if (flashMode == ImageCapture.FLASH_MODE_ON) {
            setTorchEnabled(true)
        }
        playSound(android.media.MediaActionSound.START_VIDEO_RECORDING)

        activeRecording = pendingRecording.start(ContextCompat.getMainExecutor(context)) { event ->
            if (event is VideoRecordEvent.Finalize) {
                activeRecording = null
                setTorchEnabled(false)
                audioZoomManager.onRecordingStopped()
            }
            onVideoRecordEvent(event)
        }
        audioZoomManager.onRecordingStarted()

        return true
    }

    fun stopVideoRecording() {
        if (activeRecording != null) playSound(android.media.MediaActionSound.STOP_VIDEO_RECORDING)
        activeRecording?.stop()
        activeRecording = null
        setTorchEnabled(false)
        audioZoomManager.onRecordingStopped()
    }

    fun isRecordingVideo(): Boolean = activeRecording != null

    private suspend fun takeSingleBitmap(capture: ImageCapture): Bitmap? =
        suspendCancellableCoroutine { cont ->
            capture.takePicture(
                cameraExecutor,
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        try {
                            val bitmap = imageProxyToBitmap(image)
                            image.close()
                            cont.resume(bitmap)
                        } catch (e: Exception) {
                            image.close()
                            cont.resumeWithException(e)
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        cont.resumeWithException(exception)
                    }
                }
            )
        }

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

        val rotationDegrees = image.imageInfo.rotationDegrees
        if (rotationDegrees != 0) {
            val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
            return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }
        return bitmap
    }

    private fun imageProxyRgbaToBitmap(image: ImageProxy): Bitmap? {
        return try {
            val width = image.width
            val height = image.height
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * width

            val bitmap = Bitmap.createBitmap(
                width + (rowPadding / pixelStride).coerceAtLeast(0),
                height,
                Bitmap.Config.ARGB_8888
            )
            buffer.rewind()
            bitmap.copyPixelsFromBuffer(buffer)

            val rotation = image.imageInfo.rotationDegrees
            val cropped = if (rowPadding == 0) bitmap else Bitmap.createBitmap(bitmap, 0, 0, width, height)
            if (rotation != 0) {
                val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                Bitmap.createBitmap(cropped, 0, 0, cropped.width, cropped.height, matrix, true)
            } else {
                cropped
            }
        } catch (e: Exception) {
            runCatching { image.toBitmap() }.getOrNull()
        }
    }

    private fun saveBitmapToMediaStore(bitmap: Bitmap): Uri? {
        val name = "IMG_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}_PXL"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/Camera")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null

        try {
            resolver.openOutputStream(uri)?.use { stream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream)
            }

            try {
                resolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                    val exif = androidx.exifinterface.media.ExifInterface(pfd.fileDescriptor)
                    val gpsLocation = locationHelper.getCurrentLocation()
                    if (gpsLocation != null) {
                        try {
                            exif.setGpsInfo(gpsLocation)
                        } catch (gpsErr: Exception) {
                            Log.w(TAG, "Failed setting GPS EXIF info: ${gpsErr.message}")
                        }
                    }
                    exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_MAKE, Build.MANUFACTURER.replaceFirstChar { it.uppercase() })
                    exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_MODEL, Build.MODEL)
                    exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_SOFTWARE, "Pixel Camera 9.7 (HDR+)")
                    val timeStr = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date())
                    exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_DATETIME, timeStr)
                    exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_DATETIME_ORIGINAL, timeStr)
                    exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_IMAGE_WIDTH, bitmap.width.toString())
                    exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_IMAGE_LENGTH, bitmap.height.toString())
                    exif.saveAttributes()
                }
            } catch (exifErr: Exception) {
                Log.w(TAG, "Failed to write EXIF attributes", exifErr)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            return uri
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save image to MediaStore", e)
            resolver.delete(uri, null, null)
            return null
        }
    }

    companion object {
        private const val TAG = "CameraManager"
        const val CAMERA_MAIN = "0"
        const val CAMERA_FRONT = "1"
        const val CAMERA_ULTRAWIDE = "3"
        private const val NIGHT_HANDHELD_CAPTURES = 2
    }
}
