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
import com.motorola.pixelcamera.computational.AstroStack
import com.motorola.pixelcamera.computational.FrameStack
import com.motorola.pixelcamera.computational.LongExposureStack
import com.motorola.pixelcamera.computational.ColourCleanup
import com.motorola.pixelcamera.computational.HdrProcessor
import com.motorola.pixelcamera.computational.MotionPhotoEngine
import com.motorola.pixelcamera.computational.NightSightEngine
import com.motorola.pixelcamera.computational.PanoramaEngine
import com.motorola.pixelcamera.computational.PortraitEngine
import com.motorola.pixelcamera.hardware.LocationHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import com.motorola.pixelcamera.hardware.DimensityOptimizer
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

    /** Save a DNG next to the JPEG. Applies to Photo and Pro, which capture a single hardware frame. */
    var isRawEnabled: Boolean = false
    private var rawBound = false

    /** Selfies at the front sensor's full 32 MP instead of the binned 8 MP. Photo mode only. */
    var fullResFront: Boolean = false
    private val frontFullSize by lazy { FullResCapture.fullSize(context, CAMERA_FRONT) }

    fun isFullResSupported(): Boolean = frontFullSize != null

    fun isFullResActive(mode: CameraMode): Boolean =
        fullResFront && mode == CameraMode.CAMERA && isFrontCamera() && frontFullSize != null

    // Last auto-exposure result, used to keep brightness when only one Pro value is manual.
    @Volatile private var lastAutoIso: Int = 0
    @Volatile private var lastAutoExposureNs: Long = 0L

    private val exposureTracker = object : android.hardware.camera2.CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: android.hardware.camera2.CameraCaptureSession,
            request: CaptureRequest,
            result: android.hardware.camera2.TotalCaptureResult
        ) {
            if (result.get(android.hardware.camera2.CaptureResult.CONTROL_AE_MODE) == CaptureRequest.CONTROL_AE_MODE_OFF) return
            val iso = result.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY) ?: return
            val exp = result.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME) ?: return
            lastAutoIso = iso
            lastAutoExposureNs = exp
            if (currentCameraMode == CameraMode.VIDEO && currentVideoResolution == VideoResolution.UHD && !lowLightVideo && !toldTooDark) {
                // At 4K the stream keeps 30 fps only up to about ISO 1900 (measured: 13-19 fps from
                // 2000 up), so the driver stops there and dim rooms come out dark. 1080p goes to 12800.
                val dark = result.get(android.hardware.camera2.CaptureResult.CONTROL_AE_STATE) ==
                    android.hardware.camera2.CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED
                darkFrames = if (dark && iso < DARK_4K_ISO_LIMIT) darkFrames + 1 else 0
                if (darkFrames >= DARK_4K_FRAMES) {
                    toldTooDark = true
                    onVideoTooDark?.invoke()
                }
            }
        }
    }

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

    private var loggedCameraIds = false
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
    /** Photos and bursts in progress; while any run, a settings rebind waits for them. */
    private val capturesRunning = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var rebindPending = false

    /** Runs a capture with the camera held: a rebind asked for meanwhile happens right after. */
    private suspend fun <T> holdingCamera(block: suspend () -> T): T {
        capturesRunning.incrementAndGet()
        try {
            return block()
        } finally {
            if (capturesRunning.decrementAndGet() == 0 && rebindPending) {
                rebindPending = false
                ContextCompat.getMainExecutor(context).execute { bindCameraUseCases() }
            }
        }
    }

    /**
     * Binds the use cases for the current mode and settings. A settings change during a photo or
     * burst would unbind the camera under it, so that rebind is held until the capture is done.
     */
    fun bindCameraUseCases(
        lifecycleOwner: LifecycleOwner? = null,
        previewView: PreviewView? = null
    ) {
        if (lifecycleOwner == null && previewView == null && capturesRunning.get() > 0) {
            rebindPending = true
            Log.i(TAG, "Rebind held until the capture finishes")
            return
        }
        bindNow(lifecycleOwner, previewView)
    }

    private fun bindNow(
        lifecycleOwner: LifecycleOwner? = null,
        previewView: PreviewView? = null
    ) {
        val owner = lifecycleOwner ?: currentLifecycleOwner ?: return
        val view = previewView ?: currentPreviewView ?: return
        toldTooDark = false
        darkFrames = 0
        currentLifecycleOwner = owner
        currentPreviewView = view

        val provider = cameraProvider ?: return
        if (!loggedCameraIds) {
            loggedCameraIds = true
            runCatching {
                val cm = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
                Log.i(TAG, "Camera2 ids visible to this app: ${cm.cameraIdList.joinToString()}")
                val uw = runCatching { cm.getCameraCharacteristics(CAMERA_ULTRAWIDE) }
                Log.i(TAG, "Ultrawide (id $CAMERA_ULTRAWIDE) characteristics readable: ${uw.isSuccess} ${uw.exceptionOrNull()?.message ?: ""}")
            }
        }
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
            // The front preview is always a mirror. The back preview only when its mirror is on.
            .setMirrorMode(
                if (currentCameraId != CAMERA_FRONT && mirrorBack) MirrorMode.MIRROR_MODE_ON
                else MirrorMode.MIRROR_MODE_ON_FRONT_ONLY
            )

        val previewExtender = Camera2Interop.Extender(previewBuilder)
        previewExtender.setSessionCaptureCallback(exposureTracker)
        // Video focus mode moves the lens gently, so a recording does not show focus hunting.
        previewExtender.setCaptureRequestOption(
            CaptureRequest.CONTROL_AF_MODE,
            if (currentCameraMode == CameraMode.VIDEO) CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
            else CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
        )
        previewExtender.setCaptureRequestOption(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO)
        if (isGoogleAwbEnabled) {
            previewExtender.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
        }
        if (currentCameraMode == CameraMode.NIGHT_SIGHT) {
            previewExtender.setCaptureRequestOption(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_USE_SCENE_MODE)
            previewExtender.setCaptureRequestOption(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_NIGHT)
        }
        if (currentCameraMode == CameraMode.VIDEO) {
            val targetFps = videoFpsRange()
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

        val selectedInfo = runCatching { cameraSelector.filter(provider.availableCameraInfos).firstOrNull() }.getOrNull()
        val rawSupported = selectedInfo != null && runCatching {
            ImageCapture.getImageCaptureCapabilities(selectedInfo).supportedOutputFormats
                .contains(ImageCapture.OUTPUT_FORMAT_RAW_JPEG)
        }.getOrDefault(false)
        rawBound = isRawEnabled && rawSupported &&
            (currentCameraMode == CameraMode.CAMERA || currentCameraMode == CameraMode.PRO)
        if (isRawEnabled && !rawSupported) Log.w(TAG, "RAW+JPEG is not supported on camera $currentCameraId")

        val captureBuilder = ImageCapture.Builder()
            .setOutputFormat(if (rawBound) ImageCapture.OUTPUT_FORMAT_RAW_JPEG else ImageCapture.OUTPUT_FORMAT_JPEG)
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
            .apply { if (highBitrateVideo && targetQuality == Quality.FHD) setTargetVideoEncodingBitRate(HIGH_BITRATE_FHD) }
            .setQualitySelector(
                QualitySelector.from(
                    targetQuality,
                    FallbackStrategy.lowerQualityOrHigherThan(Quality.FHD)
                )
            )
            .build()
        val wants60 = currentVideoFps == VideoFps.FPS_60 && targetQuality != Quality.UHD && currentCameraId != CAMERA_FRONT
        val videoBuilder = VideoCapture.Builder(recorder)
            .setMirrorMode(if (mirrorEnabled) MirrorMode.MIRROR_MODE_ON else MirrorMode.MIRROR_MODE_OFF)
            .setTargetFrameRate(if (wants60) android.util.Range(60, 60) else android.util.Range(30, 30))
        // The HAL only keeps full frame rate with EIS at 1080p30.
        val stabilizationSupported = selectedInfo != null && runCatching {
            Recorder.getVideoCapabilities(selectedInfo).isStabilizationSupported
        }.getOrDefault(false)
        val eis = videoStabilizationEnabled && stabilizationSupported && currentCameraMode == CameraMode.VIDEO &&
            targetQuality == Quality.FHD && !wants60
        if (eis) videoBuilder.setVideoStabilizationEnabled(true)
        Log.i(TAG, "Video config: quality=$targetQuality fps=${if (wants60) 60 else 30} eis=$eis")
        videoCapture = videoBuilder.build()

        // 4. Image Analysis Use Case (Real-time Live RGB/Luminance Histogram, Top Shot, Panorama)
        val analysisBuilder = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
        if (currentCameraMode == CameraMode.PANORAMA) {
            // The panorama is built from these frames, so they get twice the default size.
            analysisBuilder.setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(PANORAMA_FRAME_SIZE, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                    )
                    .build()
            )
        }
        val analysis = analysisBuilder.build()

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

                val qrListener = onQrDetected
                // A code stays in view for seconds, so a few looks a second are enough; scanning
                // every frame kept a processor core busy for nothing.
                val nowMs = android.os.SystemClock.elapsedRealtime()
                if (qrListener != null && currentCameraMode == CameraMode.CAMERA && nowMs - lastQrScanMs >= QR_SCAN_INTERVAL_MS) {
                    lastQrScanMs = nowMs
                    qrScanner.scan(imageProxy)?.let(qrListener)
                }

                if (topShotMode != TopShotMode.OFF && (currentCameraMode == CameraMode.CAMERA || currentCameraMode == CameraMode.PORTRAIT)) {
                    MotionPhotoEngine.offerFrame(imageProxy)
                } else {
                    MotionPhotoEngine.clear()
                }
                if (currentCameraMode == CameraMode.PANORAMA && panoramaEngine.wantsFrame()) {
                    imageProxyRgbaToBitmap(imageProxy)?.let { panoramaEngine.processIncomingFrame(it) }
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
            // No 12 MP still stream next to the recorder: it costs the HAL frame rate.
            useCases.add(videoCapture!!)
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
            if (currentCameraMode == CameraMode.PANORAMA) {
                Log.i(TAG, "Panorama frames: ${imageAnalysis?.resolutionInfo?.resolution}")
            }
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

    /** Lets go of the camera so another session (slow motion) can open it. [bindCameraUseCases] takes it back. */
    fun releaseCamera() {
        toldTooDark = false
        darkFrames = 0
        cameraProvider?.unbindAll()
        camera = null
    }

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
        val targetFps = videoFpsRange()
        val options = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, targetFps)
            .build()
        c2Control.setCaptureRequestOptions(options)
        Log.i(TAG, "Applied video FPS range: $targetFps (Res: $currentVideoResolution, FPS: $currentVideoFps)")
    }

    /**
     * Low light for video: the camera may slow down to 10 frames a second for a longer shutter and
     * more ISO. At a fixed 30 the driver stops at about ISO 1800 in 4K; with this range it went to
     * ISO 9800 at 1/10 s in the same dark scene.
     */
    var lowLightVideo = false

    fun setLowLightVideoEnabled(enabled: Boolean) {
        lowLightVideo = enabled
        toldTooDark = false
        darkFrames = 0
        if (currentCameraMode == CameraMode.VIDEO) applyVideoFps()
    }

    private fun videoFpsRange(): android.util.Range<Int> = when {
        currentVideoFps == VideoFps.FPS_60 && currentVideoResolution != VideoResolution.UHD -> android.util.Range(60, 60)
        lowLightVideo -> android.util.Range(LOW_LIGHT_VIDEO_MIN_FPS, 30)
        else -> android.util.Range(30, 30)
    }

    /** Called once per camera session, off the main thread, when 4K video is too dark for the driver to expose. */
    var onVideoTooDark: (() -> Unit)? = null
    @Volatile private var toldTooDark = false
    private var darkFrames = 0

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

        // 1. Exposure (ISO + Shutter). With AE off both values must be set, so the one left
        // on Auto is derived from the last auto-exposure to keep the same brightness.
        if (currentManualIso > 0 || currentManualExposureTimeNs > 0L) {
            val autoProduct = lastAutoIso.toDouble() * lastAutoExposureNs.toDouble() // 0 when unknown
            val iso = if (currentManualIso > 0) currentManualIso
                else if (autoProduct > 0) (autoProduct / currentManualExposureTimeNs).toInt().coerceIn(MIN_ISO, MAX_ISO)
                else 400
            val exposure = if (currentManualExposureTimeNs > 0L) currentManualExposureTimeNs
                else if (autoProduct > 0) (autoProduct / iso).toLong().coerceIn(MIN_EXPOSURE_NS, MAX_EXPOSURE_NS)
                else 16_666_666L
            optionsBuilder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            optionsBuilder.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso)
            optionsBuilder.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, exposure)
            Log.i(TAG, "Manual exposure: iso=$iso exposure=${exposure / 1_000_000.0}ms")
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

    /** Focus and meter at a point. With [lock] the camera keeps both until the next call. */
    fun focusAt(meteringPoint: MeteringPoint, lock: Boolean = false) {
        val action = FocusMeteringAction.Builder(meteringPoint, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .apply {
                if (lock) disableAutoCancel() else setAutoCancelDuration(3, java.util.concurrent.TimeUnit.SECONDS)
            }
            .build()
        camera?.cameraControl?.startFocusAndMetering(action)
    }

    /** Low-light bursts are cleaned by the aligned merge instead of the ISP's noise reduction. */
    var ownNoiseCleanup = true

    /** Photo mode merges several frames in dim light and/or when zoomed in. */
    var multiFrameLowLight = true
    var multiFrameZoom = true

    /** Mirror the saved photo and video left-to-right, remembered separately for each side. */
    var mirrorFront = false
    var mirrorBack = false
    val mirrorEnabled: Boolean get() = if (isFrontCamera()) mirrorFront else mirrorBack

    /** 1080p video at a higher bitrate than the encoder default. */
    var highBitrateVideo = false

    /** Called with the text of a QR code seen in Photo mode. */
    var onQrDetected: ((String) -> Unit)? = null
    private val qrScanner = QrScanner()
    private var lastQrScanMs = 0L

    @OptIn(ExperimentalCamera2Interop::class)
    private fun setIspCleanup(enabled: Boolean) {
        val cam = camera ?: return
        val options = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(
                CaptureRequest.NOISE_REDUCTION_MODE,
                if (enabled) CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY else CaptureRequest.NOISE_REDUCTION_MODE_OFF
            )
            .setCaptureRequestOption(
                CaptureRequest.EDGE_MODE,
                if (enabled) CaptureRequest.EDGE_MODE_HIGH_QUALITY else CaptureRequest.EDGE_MODE_OFF
            )
            .build()
        runCatching {
            val applied = Camera2CameraControl.from(cam.cameraControl).addCaptureRequestOptions(options)
            // Only switching off has to be in effect before the next frame; restoring can settle on its own.
            if (!enabled) applied.get(1, java.util.concurrent.TimeUnit.SECONDS)
        }.onFailure { Log.w(TAG, "Could not switch ISP cleanup: ${it.message}") }
    }

    private fun isLowLight(): Boolean = lastAutoIso >= LOW_LIGHT_ISO

    /**
     * Frames merged for a Photo-mode shot right now. 1 means a plain single shot.
     * High ISO single frames are grainy, and a zoomed crop magnifies whatever noise there is,
     * so both are shot as a short aligned burst.
     */
    fun multiFrameCount(): Int = when {
        rawBound || flashMode != ImageCapture.FLASH_MODE_OFF -> 1
        // Portrait merges in low light only: its single frame at a slow shutter is easily shaken.
        currentCameraMode == CameraMode.PORTRAIT -> lowLightFrames()
        currentCameraMode != CameraMode.CAMERA -> 1
        lowLightFrames() > 1 -> lowLightFrames()
        multiFrameZoom && currentZoomRatio >= ZOOM_MERGE_RATIO -> 3
        else -> 1
    }

    /** Frames to merge for the current light level: more as the sensor gain climbs. */
    /** With the ISP's noise reduction off for a burst, the merge needs its own colour cleanup. */
    private fun cleanupStrength(ownCleanup: Boolean): Float =
        if (ownCleanup) ColourCleanup.strengthForIso(lastAutoIso) else 0f

    private fun lowLightFrames(): Int = when {
        !multiFrameLowLight -> 1
        lastAutoIso >= LOW_LIGHT_ISO_HIGH -> 4
        isLowLight() -> 3
        else -> 1
    }

    /** True when the next Photo-mode shot is a low-light burst (as opposed to a zoom burst). */
    fun isLowLightBurst(): Boolean = multiFrameLowLight && multiFrameCount() > 1 && isLowLight()

    /**
     * Executes photo capture with maximum clarity and appropriate computational processing
     */
    suspend fun capturePhoto(
        mode: CameraMode,
        portraitBlurStrength: Float = 14f,
        portraitFocusX: Float = 0.5f,
        portraitFocusY: Float = 0.45f,
        onProgress: (CaptureProgress) -> Unit
    ): Uri? = holdingCamera { withContext(Dispatchers.IO) {
        val capture = imageCapture ?: return@withContext null
        if (mode != CameraMode.PANORAMA) playSound(android.media.MediaActionSound.SHUTTER_CLICK)

        /**
         * Takes [count] frames, reporting each one, then switches the UI to processing.
         * Each JPEG is decoded on the compute pool while the next frame is being exposed.
         */
        suspend fun burst(count: Int): List<Bitmap> = coroutineScope {
            val mirror = mirrorEnabled
            val t0 = android.os.SystemClock.elapsedRealtime()
            onProgress(CaptureProgress.Frames(0, count))
            val decoding = (0 until count).mapNotNull { i ->
                val jpeg = runCatching { takeJpegFrame(capture) }.getOrNull()
                onProgress(CaptureProgress.Frames(i + 1, count))
                jpeg?.let { async(DimensityOptimizer.computationDispatcher) { runCatching { it.decode(mirror) }.getOrNull() } }
            }
            val tCaptured = android.os.SystemClock.elapsedRealtime()
            onProgress(CaptureProgress.Processing)
            val frames = decoding.awaitAll().filterNotNull()
            Log.i(TAG, "Burst of $count: capture=${tCaptured - t0}ms decode tail=${android.os.SystemClock.elapsedRealtime() - tCaptured}ms")
            frames
        }

        /**
         * Takes [count] frames and hands each to [stack] as soon as it is decoded, then frees it,
         * so no more than two full-size frames are ever in memory. Returns how many went in.
         */
        suspend fun burstInto(count: Int, stack: FrameStack): Int = coroutineScope {
            val mirror = mirrorEnabled
            onProgress(CaptureProgress.Frames(0, count))
            val decodes = Semaphore(2)
            val adding = Mutex()
            val jobs = (0 until count).mapNotNull { i ->
                val jpeg = runCatching { takeJpegFrame(capture) }.getOrNull()
                onProgress(CaptureProgress.Frames(i + 1, count))
                jpeg?.let {
                    launch(DimensityOptimizer.computationDispatcher) {
                        decodes.withPermit {
                            val bitmap = runCatching { it.decode(mirror) }.getOrNull() ?: return@withPermit
                            try {
                                adding.withLock { stack.add(bitmap) }
                            } finally {
                                bitmap.recycle()
                            }
                        }
                    }
                }
            }
            onProgress(CaptureProgress.Processing)
            jobs.joinAll()
            stack.count
        }

        /** Saves the merge of [stack], or a plain photo when no frame made it in. */
        suspend fun saveStack(stack: FrameStack): Uri? {
            val merged = withContext(DimensityOptimizer.computationDispatcher) { stack.result() }
                ?: return takePictureDirect(capture)
            return try { saveBitmapToMediaStore(merged) } finally { merged.recycle() }
        }

        suspend fun single(): Uri? {
            onProgress(CaptureProgress.Frames(0, 1))
            return if (rawBound) takePictureRawAndJpeg(capture) else takePictureDirect(capture)
        }

        suspend fun attachMotion(savedUri: Uri?, motionFrames: List<MotionPhotoEngine.MotionFrame>) {
            val tempMp4 = File(context.cacheDir, "motion_${System.currentTimeMillis()}.mp4")
            try {
                if (savedUri == null || motionFrames.size < 4) return
                if (MotionPhotoEngine.encodeFramesToMp4(motionFrames, tempMp4)) {
                    MotionPhotoEngine.attachMotionPhoto(context, savedUri, tempMp4)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Motion Photo attach warning: ${e.message}")
            } finally {
                tempMp4.delete()
                MotionPhotoEngine.recycleFrames(motionFrames)
            }
        }

        when (mode) {
            // Manual exposure: keep the single hardware frame exactly as shot.
            CameraMode.PRO -> single()
            CameraMode.CAMERA -> {
                // Unbinned pixels are tiny: in dim light the full-size frame is mostly noise,
                // so the binned multi-frame photo below is the better picture there.
                if (isFullResActive(mode) && !isLowLight()) {
                    onProgress(CaptureProgress.Processing)
                    return@withContext captureFullRes()
                }
                val motionFrames = if (topShotMode != TopShotMode.OFF) MotionPhotoEngine.snapshotFrames() else emptyList()
                val multiFrames = multiFrameCount()
                val hdrFrames = if (hdrPlusMode == 2) 3 else if (hdrPlusMode == 1) 2 else 1
                val toneMapped = hdrPlusMode != 0 || isGoogleAwbEnabled

                val savedUri = if (rawBound || (!toneMapped && multiFrames <= 1)) {
                    single()
                } else {
                    // Low light with HDR+ off: the aligned merge is the noise cleanup, so the ISP's own
                    // noise reduction and sharpening (which smear high-ISO noise into worms) are off.
                    val ownCleanup = ownNoiseCleanup && !toneMapped && isLowLightBurst()
                    val t0 = android.os.SystemClock.elapsedRealtime()
                    if (ownCleanup) setIspCleanup(false)
                    val frames = try {
                        burst(maxOf(hdrFrames, multiFrames))
                    } finally {
                        if (ownCleanup) setIspCleanup(true)
                    }
                    Log.i(TAG, "Photo burst capture: ${android.os.SystemClock.elapsedRealtime() - t0}ms ownCleanup=$ownCleanup")
                    Log.i(TAG, "Photo burst: ${frames.size} frames, iso=$lastAutoIso, toneMapped=$toneMapped")
                    when {
                        frames.isEmpty() -> takePictureDirect(capture)
                        toneMapped -> saveBitmapToMediaStore(HdrProcessor.processHdr(frames, enableGoogleAwb = isGoogleAwbEnabled))
                        // Low light with HDR+ off: merge only, so the look stays the ISP's own
                        else -> saveBitmapToMediaStore(HdrProcessor.mergeBurst(frames, cleanupStrength(ownCleanup)))
                    }
                }
                attachMotion(savedUri, motionFrames)
                savedUri
            }
            CameraMode.PORTRAIT -> {
                val motionFrames = if (topShotMode != TopShotMode.OFF) MotionPhotoEngine.snapshotFrames() else emptyList()
                // In low light, merge a short burst first: the merge starts from the sharpest frame,
                // so one shaken exposure no longer ruins the shot, and the noise averages out.
                val frameCount = multiFrameCount()
                val ownCleanup = ownNoiseCleanup && frameCount > 1
                if (ownCleanup) setIspCleanup(false)
                val frames = try {
                    burst(frameCount)
                } finally {
                    if (ownCleanup) setIspCleanup(true)
                }
                val baseBitmap = when {
                    frames.isEmpty() -> return@withContext null
                    frames.size == 1 -> frames[0]
                    else -> HdrProcessor.mergeBurst(frames, cleanupStrength(ownCleanup))
                }
                val portraitBitmap = PortraitEngine.applyPortraitBokeh(
                    context,
                    baseBitmap,
                    focusX = portraitFocusX,
                    focusY = portraitFocusY,
                    blurIntensity = portraitBlurStrength
                )
                val savedUri = saveBitmapToMediaStore(portraitBitmap)
                attachMotion(savedUri, motionFrames)
                savedUri
            }
            CameraMode.NIGHT_SIGHT -> {
                // Handheld: each capture is already a 6-frame MediaTek MFNR merge (~1.5s in the HAL),
                // so more captures only add hold time and hand drift.
                if (isAstrophotographyActive) {
                    val stack = AstroStack()
                    burstInto(ASTRO_FRAMES, stack)
                    saveStack(stack)
                } else {
                    val frames = burst(NIGHT_HANDHELD_CAPTURES)
                    if (frames.isEmpty()) takePictureDirect(capture)
                    else saveBitmapToMediaStore(NightSightEngine.processNightSight(frames))
                }
            }
            CameraMode.LONG_EXPOSURE -> {
                val stack = LongExposureStack()
                burstInto(LONG_EXPOSURE_FRAMES, stack)
                saveStack(stack)
            }
            CameraMode.PANORAMA -> {
                if (panoramaEngine.isCapturing()) {
                    onProgress(CaptureProgress.Processing)
                    panoramaEngine.finishCapture(locationHelper.getCurrentLocation())
                } else {
                    startPanoramaCapture()
                    null
                }
            }
            // Video is handled by startRecording / stopRecording
            CameraMode.VIDEO -> takePictureDirect(capture)
        }
    } }

    fun startPanoramaCapture() {
        panoramaFocalToWidth()?.let { panoramaEngine.focalToWidth = it }
        panoramaEngine.startCapture()
    }

    /** Lens focal length over the sensor's short side, times the zoom: the stitcher's focal length per frame width. */
    private fun panoramaFocalToWidth(): Float? = runCatching {
        val info = camera?.cameraInfo ?: return null
        val c2 = Camera2CameraInfo.from(info)
        val focal = c2.getCameraCharacteristic(android.hardware.camera2.CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?.firstOrNull() ?: return null
        val sensor = c2.getCameraCharacteristic(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            ?: return null
        val zoom = info.zoomState.value?.zoomRatio ?: 1f
        (focal / minOf(sensor.width, sensor.height) * zoom).takeIf { it in 0.3f..5f }
    }.getOrNull()

    suspend fun stopPanoramaCapture(): Uri? {
        return panoramaEngine.finishCapture(locationHelper.getCurrentLocation())
    }

    fun isCapturingPanorama(): Boolean = panoramaEngine.isCapturing()

    /**
     * Direct CameraX output into MediaStore: Preserves hardware ISP demosaicing,
     * hardware edge enhancement, and all EXIF tags without lossy software recompression.
     */
    private suspend fun takePictureDirect(capture: ImageCapture): Uri? =
        // With RAW on, the camera hands over two files and refuses a request that names only one
        // (that crashed the hold-the-shutter burst).
        if (capture.outputFormat == ImageCapture.OUTPUT_FORMAT_RAW_JPEG) takePictureRawAndJpeg(capture)
        else takeJpegToMediaStore(capture)

    private suspend fun takeJpegToMediaStore(capture: ImageCapture): Uri? =
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
            ).setMetadata(
                ImageCapture.Metadata().apply {
                    if (gpsLocation != null) location = gpsLocation
                    isReversedHorizontal = mirrorEnabled
                }
            ).build()

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
     * Full-size selfie. CameraX cannot ask for this size, so it hands the camera over for one
     * shot and takes it back. If the driver does not deliver, the normal photo is taken instead.
     */
    private suspend fun captureFullRes(): Uri? {
        val size = frontFullSize ?: return null
        val mirror = mirrorEnabled
        val exposureIndex = getExposureCompensationIndex()
        withContext(Dispatchers.Main) { cameraProvider?.unbindAll() }
        val jpeg = try {
            FullResCapture.capture(context, CAMERA_FRONT, size, exposureIndex)
        } finally {
            withContext(Dispatchers.Main) { bindNow() }
        }
        if (jpeg == null) {
            Log.w(TAG, "Full-size capture failed, taking the normal photo")
            return imageCapture?.let { takePictureDirect(it) }
        }
        return saveJpegToMediaStore(jpeg, mirror)
    }

    /** Stores a finished JPEG as it is; mirroring is recorded in its orientation tag. */
    private fun saveJpegToMediaStore(
        jpeg: ByteArray, mirror: Boolean,
        name: String = "IMG_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}_PXL"
    ): Uri? {
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
        return try {
            resolver.openOutputStream(uri)?.use { it.write(jpeg) } ?: error("no output stream")
            runCatching {
                resolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                    val exif = androidx.exifinterface.media.ExifInterface(pfd.fileDescriptor)
                    if (mirror) exif.flipHorizontally()
                    locationHelper.getCurrentLocation()?.let { runCatching { exif.setGpsInfo(it) } }
                    exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_SOFTWARE, "Pixel Camera kyoto")
                    exif.saveAttributes()
                }
            }.onFailure { Log.w(TAG, "Failed to write EXIF attributes", it) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            Log.i(TAG, "Full-size photo saved: $uri")
            uri
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save full-size photo", e)
            resolver.delete(uri, null, null)
            null
        }
    }

    /**
     * RAW + JPEG: CameraX writes the sensor frame as a DNG and the ISP result as a JPEG.
     * Returns the JPEG uri (the one the gallery preview and Motion Photo use).
     */
    private suspend fun takePictureRawAndJpeg(capture: ImageCapture): Uri? =
        suspendCancellableCoroutine { cont ->
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val name = "IMG_${timeStamp}_PXL"
            val gpsLocation = locationHelper.getCurrentLocation()
            fun options(ext: String, mime: String) = ImageCapture.OutputFileOptions.Builder(
                context.contentResolver,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.$ext")
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/Camera")
                }
            ).setMetadata(
                ImageCapture.Metadata().apply {
                    if (gpsLocation != null) location = gpsLocation
                    // The DNG stays as the sensor saw it; only the JPEG is mirrored
                    isReversedHorizontal = mirrorEnabled && ext == "jpg"
                }
            ).build()

            var jpegUri: Uri? = null
            var saved = 0
            capture.takePicture(
                options("dng", "image/x-adobe-dng"),
                options("jpg", "image/jpeg"),
                cameraExecutor,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        saved++
                        val uri = outputFileResults.savedUri
                        val isJpeg = outputFileResults.imageFormat == android.graphics.ImageFormat.JPEG
                        Log.i(TAG, "RAW+JPEG part saved (${if (isJpeg) "JPEG" else "DNG"}): $uri")
                        if (isJpeg) jpegUri = uri
                        if (saved == 2 && cont.isActive) cont.resume(jpegUri)
                    }

                    override fun onError(exception: ImageCaptureException) {
                        Log.e(TAG, "RAW+JPEG capture failed: ${exception.message}", exception)
                        if (cont.isActive) cont.resume(jpegUri)
                    }
                }
            )
        }

    fun isRawActive(): Boolean = rawBound

    /**
     * Start high-definition Video Recording directly to DCIM/Camera
     */
    fun startVideoRecording(
        onVideoRecordEvent: (VideoRecordEvent) -> Unit
    ): Boolean {
        val video = videoCapture ?: return false
        // A stopped recording takes a moment to finish its file; starting another before that
        // makes the recorder throw.
        if (activeRecording != null || recordingFinalizing) return false

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
                recordingFinalizing = false
                setTorchEnabled(false)
                audioZoomManager.onRecordingStopped()
            }
            onVideoRecordEvent(event)
        }
        audioZoomManager.onRecordingStarted()

        return true
    }

    fun stopVideoRecording() {
        val recording = activeRecording ?: return
        playSound(android.media.MediaActionSound.STOP_VIDEO_RECORDING)
        recordingFinalizing = true
        recording.stop()
        activeRecording = null
        setTorchEnabled(false)
        audioZoomManager.onRecordingStopped()
    }

    fun isRecordingVideo(): Boolean = activeRecording != null

    /** True between stopping a recording and its file being finished. */
    @Volatile private var recordingFinalizing = false

    /** Frees what outlives a camera session. Call when the screen that owns this object is destroyed. */
    fun release() {
        runCatching { panoramaEngine.release() }
        runCatching { actionSound.release() }
        cameraExecutor.shutdown()
    }

    fun pauseVideoRecording() { activeRecording?.pause() }

    fun resumeVideoRecording() { activeRecording?.resume() }

    /** Saves a frame grabbed from the viewfinder while recording (video resolution, not 12 MP). */
    suspend fun saveVideoSnapshot(frame: Bitmap): Uri? = withContext(Dispatchers.IO) {
        saveBitmapToMediaStore(frame)
    }

    /**
     * The hold-the-shutter burst: one frame after another while [isHeld] says so, up to [max].
     * Frames are taken into memory and written to storage in the background, so the camera never
     * waits for a file. [onShot] gets the running count. Returns the last photo saved.
     *
     * With RAW on, each shot is a RAW and JPEG pair written by the camera itself, which is slower.
     */
    suspend fun captureBurst(isHeld: () -> Boolean, max: Int, onShot: suspend (Int) -> Unit): Uri? = holdingCamera { coroutineScope {
        val capture = imageCapture ?: return@coroutineScope null
        val raw = capture.outputFormat == ImageCapture.OUTPUT_FORMAT_RAW_JPEG
        val mirror = mirrorEnabled
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val saves = ArrayDeque<kotlinx.coroutines.Deferred<Uri?>>()
        var last: Uri? = null
        var taken = 0
        val started = android.os.SystemClock.elapsedRealtime()
        while (isHeld() && taken < max) {
            playSound(android.media.MediaActionSound.SHUTTER_CLICK)
            if (raw) {
                last = withContext(Dispatchers.IO) { takePictureRawAndJpeg(capture) } ?: break
            } else {
                val frame = runCatching { withContext(Dispatchers.IO) { takeJpegFrame(capture) } }.getOrNull() ?: break
                val name = "IMG_${stamp}_%02d_PXL".format(taken + 1)
                saves.addLast(async(Dispatchers.IO) { saveJpegToMediaStore(frame.bytes, mirror, name) })
                // Each frame is about 10 MB: never hold more than a few waiting for storage.
                while (saves.size > BURST_PENDING_SAVES) saves.removeFirst().await()?.let { last = it }
            }
            taken++
            onShot(taken)
        }
        for (save in saves) save.await()?.let { last = it }
        Log.i(TAG, "Burst: $taken photos in ${android.os.SystemClock.elapsedRealtime() - started} ms (raw=$raw)")
        last
    } }

    /** One captured JPEG, copied out of the camera buffer so the camera can take the next frame. */
    private class JpegFrame(val bytes: ByteArray, val rotationDegrees: Int) {
        fun decode(mirror: Boolean): Bitmap {
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (rotationDegrees == 0 && !mirror) return bitmap
            val matrix = Matrix().apply {
                postRotate(rotationDegrees.toFloat())
                if (mirror) postScale(-1f, 1f)
            }
            // Free the unrotated copy at once: two full-size frames per decode were the peak.
            return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                .also { if (it !== bitmap) bitmap.recycle() }
        }
    }

    private suspend fun takeJpegFrame(capture: ImageCapture): JpegFrame =
        suspendCancellableCoroutine { cont ->
            capture.takePicture(
                cameraExecutor,
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        try {
                            val buffer = image.planes[0].buffer
                            val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
                            cont.resume(JpegFrame(bytes, image.imageInfo.rotationDegrees))
                        } catch (e: Exception) {
                            cont.resumeWithException(e)
                        } finally {
                            image.close()
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        cont.resumeWithException(exception)
                    }
                }
            )
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
                    exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_SOFTWARE, "Pixel Camera kyoto")
                    writeExposureExif(exif)
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

    /**
     * Merged photos are re-encoded, so the camera's own EXIF is gone. Exposure values come from the
     * auto-exposure reading at the moment of the shot; lens values from the camera's static info.
     */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun writeExposureExif(exif: androidx.exifinterface.media.ExifInterface) {
        val iso = if (currentManualIso > 0) currentManualIso else lastAutoIso
        val exposureNs = if (currentManualExposureTimeNs > 0) currentManualExposureTimeNs else lastAutoExposureNs
        if (iso > 0) exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, iso.toString())
        if (exposureNs > 0) exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_EXPOSURE_TIME, (exposureNs / 1e9).toString())
        val info = camera?.cameraInfo?.let { runCatching { Camera2CameraInfo.from(it) }.getOrNull() } ?: return
        info.getCameraCharacteristic(android.hardware.camera2.CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
            ?.firstOrNull()?.let { exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_F_NUMBER, it.toString()) }
        info.getCameraCharacteristic(android.hardware.camera2.CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?.firstOrNull()?.let {
                exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_FOCAL_LENGTH, "${(it * 1000).toInt()}/1000")
            }
    }

    companion object {
        private val PANORAMA_FRAME_SIZE = Size(1280, 960)
        private const val ZOOM_MERGE_RATIO = 2f
        private const val HIGH_BITRATE_FHD = 36_000_000
        private const val TAG = "CameraManager"
        const val CAMERA_MAIN = "0"
        const val CAMERA_FRONT = "1"
        const val CAMERA_ULTRAWIDE = "3"
        private const val NIGHT_HANDHELD_CAPTURES = 2
        private const val ASTRO_FRAMES = 8
        private const val LONG_EXPOSURE_FRAMES = 12
        private const val DARK_4K_ISO_LIMIT = 2500
        private const val QR_SCAN_INTERVAL_MS = 300L
        private const val BURST_PENDING_SAVES = 3
        private const val LOW_LIGHT_VIDEO_MIN_FPS = 10
        /** Two seconds at 30 frames a second. */
        private const val DARK_4K_FRAMES = 60
        private const val LOW_LIGHT_ISO = 800
        private const val LOW_LIGHT_ISO_HIGH = 3200
        // Sensor limits of the main camera (from the HAL static metadata).
        private const val MIN_ISO = 100
        private const val MAX_ISO = 6400
        private const val MIN_EXPOSURE_NS = 100_000L
        private const val MAX_EXPOSURE_NS = 200_000_000L
    }
}
