package com.motorola.pixelcamera.ui

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.util.Size
import android.graphics.PointF
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import java.util.Locale
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.ImageCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CaptureRequest
import android.view.HapticFeedbackConstants
import androidx.lifecycle.lifecycleScope
import com.motorola.pixelcamera.computational.PortraitSegmenter
import com.motorola.pixelcamera.R
import com.motorola.pixelcamera.computational.MotionPhotoEngine
import com.motorola.pixelcamera.core.CameraManager
import com.motorola.pixelcamera.core.CameraMode
import com.motorola.pixelcamera.core.CaptureProgress
import com.motorola.pixelcamera.databinding.ActivityMainBinding
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraManager: CameraManager
    private lateinit var prefs: SharedPreferences

    private var currentMode: CameraMode = CameraMode.CAMERA
    private var isCapturing = false

    // Quick Settings states
    private var moreLightMode = 0 // 0: None, 1: Night Auto, 2: Flash On
    private var hdrMode = 0   // HDR+ merge: 0: Off, 1: On (2 frames), 2: Enhanced (3 frames)
    private var timerDuration = 0 // 0: Off, 3: 3s, 10: 10s

    // Tap to Focus & Interactive Brightness Exposure Slider
    private var reticleAutoHideJob: Job? = null
    private var isDraggingExposure = false
    private var dragStartY = 0f
    private var initialEvIndex = 0
    private var currentEvIndex = 0

    // Video Recording Timer & Animation Jobs
    private var videoTimerJob: Job? = null
    private var recordingDotAnimator: ObjectAnimator? = null
    private var recordingSeconds = 0

    // Aperture blur strength for Portrait mode (progress 0..100)
    private var portraitBlurProgress = 50
    private var latestCapturedUri: Uri? = null
    private var isSelfieWarmLightActive = false

    // Gyroscope / Accelerometer Horizon Level Indicator
    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null
    private var lightSensor: Sensor? = null
    // Astrophotography needs darkness as well as stillness; unknown light counts as dark.
    private var ambientLux: Float = 0f
    private var latestGalleryUri: Uri? = null
    private var timerJob: Job? = null
    private var hintJob: Job? = null
    private var lowLightJob: Job? = null
    private var captureChipHeldByCapture = false
    private var shutterHeld = false
    private var videoPaused = false

    // Slow motion runs outside CameraX, on its own camera session.
    private val slowMotion by lazy {
        com.motorola.pixelcamera.core.SlowMotionCamera(applicationContext).apply {
            // Another app or the system took the camera: go back to the normal preview.
            onLost = {
                if (slowMode != null && !isDestroyed) {
                    stopVideoRecordingUi()
                    leaveSlowMotion(rebind = true)
                    showHint("Slow motion stopped · camera was taken by another app")
                }
            }
        }
    }
    private var slowMode: com.motorola.pixelcamera.core.SlowMotionCamera.Mode? = null
    private var slowModeSwitching = false
    private var lastTapFocus: PointF? = null   // where the last tap lands in the saved photo
    private var qrText: String? = null
    private var qrHideJob: Job? = null
    private var lastEisPref: Boolean? = null
    private var filteredRoll = 0f
    private var isLevelAligned = false

    // Pro Manual Controls State
    enum class ProTab { ISO, SHUTTER, FOCUS, WB, EV }
    private var activeProTab = ProTab.ISO

    private var proIsoIndex = 0
    private var proShutterIndex = 0
    private var proFocusProgress = 0
    private var proWbIndex = 0

    private val isoValues = intArrayOf(0, 100, 200, 400, 800, 1600, 3200, 6400, 12800)
    private val shutterLabels = arrayOf("AUTO", "1/4000s", "1/2000s", "1/1000s", "1/500s", "1/250s", "1/125s", "1/60s", "1/30s", "1/15s", "1/8s", "1/5s")
    private val shutterNanos = longArrayOf(0L, 250_000L, 500_000L, 1_000_000L, 2_000_000L, 4_000_000L, 8_000_000L, 16_666_666L, 33_333_333L, 66_666_666L, 125_000_000L, 200_000_000L)
    private val wbLabels = arrayOf("AWB AUTO", "2800K Incandescent", "4000K Fluorescent", "5500K Daylight", "6500K Cloudy", "7500K Shade")
    private val wbModes = intArrayOf(
        CaptureRequest.CONTROL_AWB_MODE_AUTO,
        CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT,
        CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT,
        CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT,
        CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT,
        CaptureRequest.CONTROL_AWB_MODE_SHADE
    )

    private lateinit var scaleGestureDetector: ScaleGestureDetector
    private lateinit var gestureDetector: GestureDetector
    private var gridMode: Int = 1 // 0: Off, 1: 3x3, 2: Golden Ratio
    private var isProTabActive: Boolean = false
    private var lastTotalAcc: Float = 9.8f
    private var stillStartTime: Long = 0L

    // The level indicator is fed by the motion sensor many times a second. Every call that touches
    // a view makes the whole screen redraw, so views are only touched when what they show changes.
    private val levelColours = HashMap<View, Int>()
    private var levelTextColourNow = 0

    private fun levelColour(view: View, colour: Int) {
        if (levelColours.put(view, colour) != colour) view.setBackgroundColor(colour)
    }

    private fun levelTextColour(colour: Int) {
        if (levelTextColourNow != colour) {
            levelTextColourNow = colour
            binding.tvHorizonAngle.setTextColor(colour)
        }
    }

    private fun levelText(text: String) {
        if (binding.tvHorizonAngle.text.toString() != text) binding.tvHorizonAngle.text = text
    }

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            if (event == null) return
            if (event.sensor.type == Sensor.TYPE_LIGHT) {
                ambientLux = event.values[0]
                return
            }
            if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

            val ax = event.values[0]
            val ay = event.values[1]
            val az = event.values[2]

            // Astrophotography Stability Detection in Night Sight mode
            val totalAcc = kotlin.math.sqrt((ax * ax + ay * ay + az * az).toDouble()).toFloat()
            val deltaAcc = abs(totalAcc - lastTotalAcc)
            lastTotalAcc = totalAcc

            if (currentMode == CameraMode.NIGHT_SIGHT && !isCapturing) {
                if (deltaAcc < 0.08f && ambientLux < ASTRO_MAX_LUX) {
                    if (stillStartTime == 0L) {
                        stillStartTime = System.currentTimeMillis()
                    } else if (System.currentTimeMillis() - stillStartTime >= 2500L) {
                        if (!cameraManager.isAstrophotographyActive) {
                            cameraManager.isAstrophotographyActive = true
                            updateProControlsButton()
                            binding.tvHdrBadge.text = "★ ASTROPHOTOGRAPHY"
                            binding.tvHdrBadge.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.pixel_accent_yellow))
                        }
                    }
                } else {
                    stillStartTime = 0L
                    if (cameraManager.isAstrophotographyActive) {
                        cameraManager.isAstrophotographyActive = false
                        binding.tvHdrBadge.text = "NIGHT SIGHT"
                        binding.tvHdrBadge.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.pixel_pill_text_active))
                        updateProControlsButton()
                    }
                }
            }

            val isFlatLay = abs(az) >= 8.2f // Top-down / table flat-lay shot

            if (isFlatLay) {
                // Mode A: Top-Down Flat-Lay 2D Crosshair
                binding.horizonRotatingBar.visibility = View.GONE
                binding.horizonFloatingReticle.visibility = View.VISIBLE
                binding.horizonLevelContainer.alpha = 0.85f

                val density = resources.displayMetrics.density
                val maxRadius = 24f * density
                val targetDx = (-ax * 6f * density).coerceIn(-maxRadius, maxRadius)
                val targetDy = (ay * 6f * density).coerceIn(-maxRadius, maxRadius)

                val distance = kotlin.math.hypot(targetDx, targetDy)
                val isNowLevel = distance < 3.5f * density

                if (isNowLevel) {
                    binding.horizonFloatingReticle.translationX = 0f
                    binding.horizonFloatingReticle.translationY = 0f
                    levelText("0°")

                    val goldColor = ContextCompat.getColor(this@MainActivity, R.color.pixel_accent_yellow)
                    levelColour(binding.horizonCrosshairH, goldColor)
                    levelColour(binding.horizonCrosshairV, goldColor)
                    levelColour(binding.horizonFloatingCrosshairH, goldColor)
                    levelColour(binding.horizonFloatingCrosshairV, goldColor)
                    levelTextColour(goldColor)

                    if (!isLevelAligned) {
                        isLevelAligned = true
                        binding.horizonLevelContainer.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    }
                } else {
                    isLevelAligned = false
                    if (abs(binding.horizonFloatingReticle.translationX - targetDx) >= density ||
                        abs(binding.horizonFloatingReticle.translationY - targetDy) >= density) {
                        binding.horizonFloatingReticle.translationX = targetDx
                        binding.horizonFloatingReticle.translationY = targetDy
                    }

                    val deg = kotlin.math.round(Math.toDegrees(atan2(distance.toDouble(), (9.8 * 6f * density)))).toInt()
                    levelText("${deg}°")

                    val tiltColor = Color.parseColor("#CCFFFFFF")
                    val goldColor = ContextCompat.getColor(this@MainActivity, R.color.pixel_accent_yellow)
                    levelColour(binding.horizonCrosshairH, goldColor)
                    levelColour(binding.horizonCrosshairV, goldColor)
                    levelColour(binding.horizonFloatingCrosshairH, tiltColor)
                    levelColour(binding.horizonFloatingCrosshairV, tiltColor)
                    levelTextColour(tiltColor)
                }
            } else {
                // Mode B: Upright Photography Horizon Wings (Portrait / Landscape Roll)
                binding.horizonFloatingReticle.visibility = View.GONE
                binding.horizonRotatingBar.visibility = View.VISIBLE
                binding.horizonLevelContainer.alpha = 0.85f

                val roll = Math.toDegrees(atan2(-ax.toDouble(), ay.toDouble())).toFloat()

                val targetOrientation = when {
                    roll in -45f..45f -> 0f
                    roll > 45f && roll <= 135f -> 90f
                    roll < -45f && roll >= -135f -> -90f
                    else -> 180f
                }

                val rawDelta = roll - targetOrientation
                filteredRoll = filteredRoll * 0.82f + rawDelta * 0.18f

                val absDelta = abs(filteredRoll)
                val isNowLevel = absDelta <= 1.0f

                if (isNowLevel) {
                    binding.horizonRotatingBar.rotation = 0f
                    levelText("0°")

                    val goldColor = ContextCompat.getColor(this@MainActivity, R.color.pixel_accent_yellow)
                    levelColour(binding.horizonWingLeft, goldColor)
                    levelColour(binding.horizonWingRight, goldColor)
                    levelColour(binding.horizonCrosshairH, goldColor)
                    levelColour(binding.horizonCrosshairV, goldColor)
                    levelTextColour(goldColor)

                    if (!isLevelAligned) {
                        isLevelAligned = true
                        binding.horizonLevelContainer.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    }
                } else {
                    isLevelAligned = false
                    if (abs(binding.horizonRotatingBar.rotation + filteredRoll) >= 0.3f) binding.horizonRotatingBar.rotation = -filteredRoll
                    val deg = absDelta.roundToInt()
                    levelText("${deg}°")

                    val tiltColor = Color.parseColor("#CCFFFFFF")
                    val goldColor = ContextCompat.getColor(this@MainActivity, R.color.pixel_accent_yellow)
                    levelColour(binding.horizonWingLeft, tiltColor)
                    levelColour(binding.horizonWingRight, tiltColor)
                    levelColour(binding.horizonCrosshairH, goldColor)
                    levelColour(binding.horizonCrosshairV, goldColor)
                    levelTextColour(tiltColor)
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] ?: false
        val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false

        if (cameraGranted && audioGranted) {
            cameraManager.locationHelper.startListening()
            setupCamera()
            loadLatestGalleryThumbnail()
        } else {
            Toast.makeText(this, "Camera and Audio permissions are required", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        hideStatusBar()

        prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)
        cameraManager = CameraManager(this)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        lightSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT)
        // Grid off unless chosen, as Google Camera. Switched off once when the look changed to match it.
        if (!prefs.getBoolean(KEY_GC_LOOK_APPLIED, false)) {
            prefs.edit().putInt(SettingsActivity.KEY_GRID_MODE, 0).putBoolean(KEY_GC_LOOK_APPLIED, true).apply()
        }
        gridMode = prefs.getInt(SettingsActivity.KEY_GRID_MODE, 0)

        // Live Histogram callback for Pro Mode
        cameraManager.onHistogramUpdate = { r, g, b ->
            if (currentMode == CameraMode.PRO) {
                runOnUiThread {
                    binding.histogramView.updateHistogram(r, g, b)
                }
            }
        }

        scaleGestureDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val currentZoom = cameraManager.currentZoomRatio
                val scale = detector.scaleFactor
                val newZoom = (currentZoom * scale).coerceIn(1.0f, cameraManager.maxZoomRatio())
                cameraManager.setZoom(newZoom)
                updateZoomUiForRatio(newZoom)
                return true
            }
        })

        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                flipCameraWithAnimation()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (binding.quickSettingsContainer.visibility == View.VISIBLE) return
                val point = binding.viewFinder.meteringPointFactory.createPoint(e.x, e.y)
                cameraManager.focusAt(point, lock = true)
                isDraggingExposure = false
                setFocusLocked(true)
                binding.viewFinder.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 != null && (e1.x - e2.x > 140) && kotlin.math.abs(velocityX) > 200) {
                    openLastShotInGallery()
                    return true
                }
                return false
            }
        })

        setupListeners()
        setupAudioZoom()
        setupZoomedVideoSaving()
        setupPanoramaEngine()
        setupPortraitPreview()
        initDefaultUiState()

        if (allPermissionsGranted()) {
            setupCamera()
            loadLatestGalleryThumbnail()
        } else {
            permissionLauncher.launch(REQUIRED_PERMISSIONS + OPTIONAL_PERMISSIONS)
        }
    }

    /** Full screen like Google Camera: the layout is measured from the top of the screen. */
    private fun hideStatusBar() {
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(androidx.core.view.WindowInsetsCompat.Type.statusBars())
            systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideStatusBar()
    }

    override fun onResume() {
        super.onResume()
        syncSettings()
        loadLatestGalleryThumbnail()
        cameraManager.locationHelper.startListening()
        accelerometer?.let {
            sensorManager?.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_UI)
        }
        lightSensor?.let {
            sensorManager?.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        startLowLightWatcher()
    }

    override fun onPause() {
        super.onPause()
        // Another app may need the camera; the normal preview comes back with the lifecycle.
        leaveSlowMotion(rebind = true)
        timerJob?.cancel()
        lowLightJob?.cancel()
        binding.tvCountdown.visibility = View.GONE
        cameraManager.locationHelper.stopListening()
        sensorManager?.unregisterListener(sensorListener)
    }

    private fun initDefaultUiState() {
        // Ensure Photo mode is default with warm sand pill
        selectModeUi(binding.modeCamera, smoothScroll = false)
        updatePhotoVideoToggle(isVideo = false)
        selectZoomPill(binding.btnZoom1x)
        binding.overlayPortraitAperture.visibility = View.GONE
        showProPanel(false)
        binding.histogramView.visibility = View.GONE
        binding.quickSettingsContainer.visibility = View.GONE
        updateProControlsButton()
        binding.focusReticleContainer.visibility = View.GONE
        binding.tvExposureEvBadge.visibility = View.GONE
        selectProTab(ProTab.ISO)
        binding.tvSensorInfo.text = MAIN_SENSOR_LABEL
        setGridType(gridMode)
        // Manual lens selection is not available through the camera API on this phone.
        (binding.btnQsLensAuto.parent.parent as View).visibility = View.GONE
    }

    private fun syncSettings() {
        cameraManager.isRawEnabled = prefs.getBoolean(SettingsActivity.KEY_RAW_ENABLED, false)
        cameraManager.fullResFront = prefs.getBoolean(SettingsActivity.KEY_FRONT_FULL_RES, false)
        cameraManager.lowLightVideo = prefs.getBoolean(SettingsActivity.KEY_LOW_LIGHT_VIDEO, false)
        hdrMode = prefs.getInt(SettingsActivity.KEY_HDR_MODE, 0).coerceIn(0, 2)
        cameraManager.hdrPlusMode = hdrMode
        cameraManager.shutterSoundEnabled = prefs.getBoolean(SettingsActivity.KEY_CAMERA_SOUNDS, true)

        val eisPref = prefs.getBoolean(SettingsActivity.KEY_EIS_ENABLED, true)
        cameraManager.videoStabilizationEnabled = eisPref
        if (lastEisPref != null && lastEisPref != eisPref && currentMode == CameraMode.VIDEO && !cameraManager.isRecordingVideo()) {
            cameraManager.bindCameraUseCases()
        }
        lastEisPref = eisPref

        val bitratePref = prefs.getBoolean(SettingsActivity.KEY_HIGH_BITRATE, false)
        val bitrateChanged = cameraManager.highBitrateVideo != bitratePref
        cameraManager.highBitrateVideo = bitratePref
        if (bitrateChanged && currentMode == CameraMode.VIDEO && !cameraManager.isRecordingVideo()) {
            cameraManager.bindCameraUseCases()
        }

        cameraManager.multiFrameLowLight = prefs.getBoolean(SettingsActivity.KEY_MULTI_FRAME_LOW_LIGHT, true)
        cameraManager.multiFrameZoom = prefs.getBoolean(SettingsActivity.KEY_MULTI_FRAME_ZOOM, true)
        cameraManager.mirrorFront = prefs.getBoolean(SettingsActivity.KEY_MIRROR_FRONT, false)
        cameraManager.mirrorBack = prefs.getBoolean(SettingsActivity.KEY_MIRROR_BACK, false)
        cameraManager.topShotMode = if (prefs.getBoolean(SettingsActivity.KEY_MOTION_PHOTO, false)) {
            CameraManager.TopShotMode.ON
        } else {
            CameraManager.TopShotMode.OFF
        }

        val flashPref = prefs.getInt(SettingsActivity.KEY_FLASH_MODE, ImageCapture.FLASH_MODE_OFF)
        cameraManager.flashMode = flashPref
        moreLightMode = when (flashPref) {
            ImageCapture.FLASH_MODE_ON -> 2
            ImageCapture.FLASH_MODE_AUTO -> 1
            else -> 0
        }

        val videoQualityPref = prefs.getString(SettingsActivity.KEY_VIDEO_QUALITY, "1080p")
        cameraManager.currentVideoResolution = if (videoQualityPref == "4k") CameraManager.VideoResolution.UHD else CameraManager.VideoResolution.FHD
        cameraManager.currentVideoFps = if (prefs.getInt(SettingsActivity.KEY_VIDEO_FPS, 30) == 60) CameraManager.VideoFps.FPS_60 else CameraManager.VideoFps.FPS_30

        com.motorola.pixelcamera.computational.HdrProcessor.saturationMultiplier = prefs.getInt(SettingsActivity.KEY_SATURATION, 100) / 100f
        com.motorola.pixelcamera.computational.HdrProcessor.microContrastMultiplier = prefs.getInt(SettingsActivity.KEY_MICRO_CONTRAST, 100) / 100f
        com.motorola.pixelcamera.computational.HdrProcessor.sharpnessMultiplier = prefs.getInt(SettingsActivity.KEY_SHARPNESS, 100) / 100f

        updateFlashUi(flashPref)
        updateQuickSettingsUi()
    }

    private fun updateQuickSettingsUi() {
        updateStatusChips()
        updateStorageBadge()

        if (currentMode == CameraMode.VIDEO) {
            binding.tvQsTitle.text = "Video settings"
            binding.segmentedTabsContainer.visibility = View.GONE
            binding.containerQsGeneral.visibility = View.GONE
            binding.containerQsPro.visibility = View.GONE
            binding.containerQsVideo.visibility = View.VISIBLE
            updateVideoQsUi()
            return
        }

        binding.tvQsTitle.text = if (currentMode == CameraMode.NIGHT_SIGHT) "Night Sight settings" else "Photo settings"
        binding.segmentedTabsContainer.visibility = View.VISIBLE
        binding.containerQsVideo.visibility = View.GONE
        if (isProTabActive) {
            binding.containerQsGeneral.visibility = View.GONE
            binding.containerQsPro.visibility = View.VISIBLE
        } else {
            binding.containerQsGeneral.visibility = View.VISIBLE
            binding.containerQsPro.visibility = View.GONE
        }

        // 1. More light row
        when (moreLightMode) {
            0 -> {
                binding.tvQsLightSubtitle.text = "Off"
                setQsButtonActive(binding.btnQsLightNone, true)
                setQsButtonActive(binding.btnQsLightNight, false)
                setQsButtonActive(binding.btnQsLightFlash, false)
            }
            1 -> {
                binding.tvQsLightSubtitle.text = "Auto"
                setQsButtonActive(binding.btnQsLightNone, false)
                setQsButtonActive(binding.btnQsLightNight, true)
                setQsButtonActive(binding.btnQsLightFlash, false)
            }
            2 -> {
                binding.tvQsLightSubtitle.text = "On"
                setQsButtonActive(binding.btnQsLightNone, false)
                setQsButtonActive(binding.btnQsLightNight, false)
                setQsButtonActive(binding.btnQsLightFlash, true)
            }
        }

        // 2. RAW/JPEG row
        val isRaw = cameraManager.isRawEnabled
        binding.tvQsRawSubtitle.text = if (isRaw) "RAW (DNG) + JPEG • Photo and Pro" else "JPEG only"
        setQsButtonActive(binding.btnQsRawOff, !isRaw)
        setQsButtonActive(binding.btnQsRawOn, isRaw)

        // Selfie resolution row: only the front sensor can leave pixel binning on this phone.
        val fullRes = cameraManager.fullResFront
        (binding.btnQsRes12.parent.parent as View).visibility =
            if (cameraManager.isFrontCamera() && cameraManager.isFullResSupported()) View.VISIBLE else View.GONE
        binding.tvQsResSubtitle.text = if (fullRes) "32 MP • Photo mode, in good light" else "8 MP"
        setQsTextButtonActive(binding.btnQsRes12, !fullRes)
        setQsTextButtonActive(binding.btnQsRes108, fullRes)

        // 3. Top Shot row
        when (hdrMode) {
            0 -> {
                binding.tvQsTopShotSubtitle.text = "Off • fastest"
                setQsButtonActive(binding.btnQsTopShotOff, true)
                setQsButtonActive(binding.btnQsTopShotAuto, false)
                setQsButtonActive(binding.btnQsTopShotOn, false)
            }
            1 -> {
                binding.tvQsTopShotSubtitle.text = "On • 2-frame merge"
                setQsButtonActive(binding.btnQsTopShotOff, false)
                setQsButtonActive(binding.btnQsTopShotAuto, true)
                setQsButtonActive(binding.btnQsTopShotOn, false)
            }
            2 -> {
                binding.tvQsTopShotSubtitle.text = "Enhanced • 3-frame merge"
                setQsButtonActive(binding.btnQsTopShotOff, false)
                setQsButtonActive(binding.btnQsTopShotAuto, false)
                setQsButtonActive(binding.btnQsTopShotOn, true)
            }
        }

        // 4. Aspect Ratio row
        val is169 = cameraManager.aspectRatio == AspectRatio.RATIO_16_9
        binding.tvQsRatioSubtitle.text = if (is169) "16:9 Wide" else "4:3 Standard"
        setQsButtonActive(binding.btnQsRatio43, !is169)
        setQsButtonActive(binding.btnQsRatio169, is169)

        // 6. Google AWB row
        val isAwb = cameraManager.isGoogleAwbEnabled
        binding.tvQsAwbSubtitle.text = if (isAwb) "Auto White Balance ON" else "Standard"
        setQsButtonActive(binding.btnQsAwbOff, !isAwb)
        setQsButtonActive(binding.btnQsAwbOn, isAwb)

        // 7. Timer row
        when (timerDuration) {
            0 -> {
                binding.tvQsTimerSubtitle.text = "Off"
                setQsButtonActive(binding.btnQsTimerOff, true)
                setQsButtonActive(binding.btnQsTimer3s, false)
                setQsButtonActive(binding.btnQsTimer10s, false)
            }
            3 -> {
                binding.tvQsTimerSubtitle.text = "3s"
                setQsButtonActive(binding.btnQsTimerOff, false)
                setQsButtonActive(binding.btnQsTimer3s, true)
                setQsButtonActive(binding.btnQsTimer10s, false)
            }
            10 -> {
                binding.tvQsTimerSubtitle.text = "10s"
                setQsButtonActive(binding.btnQsTimerOff, false)
                setQsButtonActive(binding.btnQsTimer3s, false)
                setQsButtonActive(binding.btnQsTimer10s, true)
            }
        }

        // 8. Grid row
        val activeBg = R.drawable.bg_qs_btn_active
        val inactiveBg = R.drawable.bg_qs_btn_inactive
        val activeTextColor = ContextCompat.getColor(this, R.color.gc_toggle_icon_active)
        val inactiveTextColor = ContextCompat.getColor(this, R.color.gc_icon)

        binding.btnQsGridOff.setBackgroundResource(if (gridMode == 0) activeBg else inactiveBg)
        binding.btnQsGridOff.setTextColor(if (gridMode == 0) activeTextColor else inactiveTextColor)

        binding.btnQsGrid3x3.setBackgroundResource(if (gridMode == 1) activeBg else inactiveBg)
        binding.btnQsGrid3x3.setTextColor(if (gridMode == 1) activeTextColor else inactiveTextColor)

        binding.btnQsGridGolden.setBackgroundResource(if (gridMode == 2) activeBg else inactiveBg)
        binding.btnQsGridGolden.setTextColor(if (gridMode == 2) activeTextColor else inactiveTextColor)
    }

    private fun updateVideoQsUi() {
        updateMiniBadge()
        if (currentMode == CameraMode.VIDEO) {
            selectModeUi(if (slowMode != null) binding.modeSlowMotion else binding.modeVideo)
            updateProControlsButton()
        }
        val isUhd = cameraManager.currentVideoResolution == CameraManager.VideoResolution.UHD
        val is60 = cameraManager.currentVideoFps == CameraManager.VideoFps.FPS_60

        // Subtitles
        val presetText = if (isUhd) {
            "4K Ultra HD • 30 fps"
        } else if (is60) {
            "Full HD • 60 fps"
        } else {
            "Full HD • 30 fps"
        }
        binding.tvQsVideoPresetSubtitle.text = presetText
        binding.tvQsVideoResSubtitle.text = if (isUhd) "4K Ultra HD (2160p)" else "Full HD (1080p)"
        binding.tvQsVideoFpsSubtitle.text = if (isUhd) "30 fps (4K)" else if (is60) "60 fps" else "30 fps"

        // Slow motion
        val slow = slowMode
        binding.tvQsSlowMotionSubtitle.text = when (slow) {
            null -> "Off"
            else -> "${slow.factor}× slower · ${slow.size.height}p · no sound"
        }
        setQsPillActive(binding.btnQsSlowOff, slow == null)
        setQsPillActive(binding.btnQsSlow4x, slow == com.motorola.pixelcamera.core.SlowMotionCamera.Mode.X4)
        setQsPillActive(binding.btnQsSlow8x, slow == com.motorola.pixelcamera.core.SlowMotionCamera.Mode.X8)

        // Preset Pills
        setQsPillActive(binding.btnQsPreset1080p30, !isUhd && !is60)
        setQsPillActive(binding.btnQsPreset1080p60, !isUhd && is60)
        setQsPillActive(binding.btnQsPreset4k30, isUhd)

        // Res Pills
        setQsPillActive(binding.btnQsVideoResFhd, !isUhd)
        setQsPillActive(binding.btnQsVideoRes4k, isUhd)

        // FPS Pills
        setQsPillActive(binding.btnQsVideoFps30, !is60)
        setQsPillActive(binding.btnQsVideoFps60, is60)
        binding.btnQsVideoFps60.alpha = if (isUhd) 0.4f else 1.0f

        // Torch
        val isTorch = cameraManager.flashMode == ImageCapture.FLASH_MODE_ON
        binding.tvQsVideoTorchSubtitle.text = if (isTorch) "Torch on" else "Off"
        setQsButtonActive(binding.btnQsVideoTorchOff, !isTorch)
        setQsButtonActive(binding.btnQsVideoTorchOn, isTorch)

        // Audio Zoom
        val lowLight = cameraManager.lowLightVideo
        binding.tvQsLowLightVideoSubtitle.text = when {
            !lowLight -> "Off"
            cameraManager.currentVideoFps == CameraManager.VideoFps.FPS_60 -> "On • not at 60 fps"
            else -> "On • brighter, slower frames"
        }
        setQsPillActive(binding.btnQsLowLightVideoOn, lowLight)
        setQsPillActive(binding.btnQsLowLightVideoOff, !lowLight)

        val isAudioZoom = cameraManager.audioZoomEnabled
        binding.tvQsAudioZoomSubtitle.text = if (isAudioZoom) "On • Focuses on subject" else "Off"
        setQsPillActive(binding.btnQsAudioZoomOn, isAudioZoom)
        setQsPillActive(binding.btnQsAudioZoomOff, !isAudioZoom)

        // Grid in Video
        binding.tvQsVideoGridSubtitle.text = when (gridMode) {
            1 -> "3×3 standard"
            2 -> "Golden ratio (Phi)"
            else -> "Off"
        }
        val activeBg = R.drawable.bg_qs_btn_active
        val inactiveBg = R.drawable.bg_qs_btn_inactive
        val activeTextColor = ContextCompat.getColor(this, R.color.gc_toggle_icon_active)
        val inactiveTextColor = ContextCompat.getColor(this, R.color.gc_icon)
        binding.btnQsVideoGridOff.setBackgroundResource(if (gridMode == 0) activeBg else inactiveBg)
        binding.btnQsVideoGridOff.setTextColor(if (gridMode == 0) activeTextColor else inactiveTextColor)
        binding.btnQsVideoGrid3x3.setBackgroundResource(if (gridMode == 1) activeBg else inactiveBg)
        binding.btnQsVideoGrid3x3.setTextColor(if (gridMode == 1) activeTextColor else inactiveTextColor)
        binding.btnQsVideoGridGolden.setBackgroundResource(if (gridMode == 2) activeBg else inactiveBg)
        binding.btnQsVideoGridGolden.setTextColor(if (gridMode == 2) activeTextColor else inactiveTextColor)
    }

    private fun setQsPillActive(view: TextView, active: Boolean) {
        if (active) {
            view.setBackgroundResource(R.drawable.bg_qs_btn_active)
            view.setTextColor(ContextCompat.getColor(this, R.color.gc_toggle_icon_active))
            view.paint.isFakeBoldText = false
        } else {
            view.setBackgroundResource(R.drawable.bg_qs_btn_inactive)
            view.setTextColor(ContextCompat.getColor(this, R.color.gc_icon))
            view.paint.isFakeBoldText = false
        }
    }

    private fun updateVideoBadgeText() {
        if (currentMode != CameraMode.VIDEO) return
        val isUhd = cameraManager.currentVideoResolution == CameraManager.VideoResolution.UHD
        val is60 = cameraManager.currentVideoFps == CameraManager.VideoFps.FPS_60
        val text = if (isUhd) "4K UHD • 30 FPS" else if (is60) "FHD • 60 FPS" else "FHD • 30 FPS"
        binding.tvHdrBadge.text = text
    }

    private fun setQsButtonActive(button: ImageView, active: Boolean) {
        if (active) {
            button.setBackgroundResource(R.drawable.bg_qs_btn_active)
            ImageViewCompat.setImageTintList(button, ContextCompat.getColorStateList(this, R.color.gc_toggle_icon_active))
        } else {
            button.setBackgroundResource(R.drawable.bg_qs_btn_inactive)
            ImageViewCompat.setImageTintList(button, ContextCompat.getColorStateList(this, R.color.gc_qs_icon))
        }
    }

    private fun updateFlashUi(mode: Int) {
        when (mode) {
            ImageCapture.FLASH_MODE_AUTO -> {
                binding.btnFlash.setImageResource(R.drawable.ic_flash_auto)
                ImageViewCompat.setImageTintList(binding.btnFlash, ContextCompat.getColorStateList(this, R.color.pixel_accent_sand))
            }
            ImageCapture.FLASH_MODE_ON -> {
                binding.btnFlash.setImageResource(R.drawable.ic_flash_on)
                ImageViewCompat.setImageTintList(binding.btnFlash, ContextCompat.getColorStateList(this, R.color.pixel_accent_sand))
            }
            else -> {
                binding.btnFlash.setImageResource(R.drawable.ic_flash_off)
                ImageViewCompat.setImageTintList(binding.btnFlash, ContextCompat.getColorStateList(this, R.color.pixel_text_secondary))
            }
        }
    }

    private fun allPermissionsGranted(): Boolean {
        return REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun setupCamera() {
        lifecycleScope.launch {
            try {
                cameraManager.initialize(this@MainActivity, binding.viewFinder)
                // The 0.6 lens button only makes sense when the ultrawide is really reachable.
                binding.btnZoomUltrawide.visibility = if (cameraManager.isUltrawideAvailable()) View.VISIBLE else View.GONE
                cameraManager.onQrDetected = { text -> runOnUiThread { showQrResult(text) } }
                cameraManager.onVideoTooDark = { runOnUiThread { showHint("Too dark for 4K · try Low light") } }
                restoreLastMode()
            } catch (e: Exception) {
                showHint("Failed to start camera: ${e.message}")
            }
        }
    }

    private fun loadLatestGalleryThumbnail() {
        lifecycleScope.launch(Dispatchers.IO) {
            val queryUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            try {
                // RAW files are saved next to their JPEG; the thumbnail shows the JPEG.
                contentResolver.query(
                    queryUri, arrayOf(MediaStore.Images.Media._ID),
                    "${MediaStore.Images.Media.MIME_TYPE} != ?", arrayOf("image/x-adobe-dng"),
                    "${MediaStore.Images.Media.DATE_ADDED} DESC"
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val contentUri = ContentUris.withAppendedId(queryUri, cursor.getLong(0))
                        latestGalleryUri = contentUri
                        val thumbnail = uprightThumbnail(contentUri, 160)
                        if (thumbnail != null) {
                            runOnUiThread { binding.ivGalleryThumbnail.setImageBitmap(thumbnail) }
                        }
                    }
                }
            } catch (e: Exception) {
                // Ignore if permission not granted yet or no photos exist
            }
        }
    }

    /**
     * A small copy of a photo turned the way it is meant to be seen. Photos are stored sideways
     * with a rotation tag (selfies also mirrored), which the system thumbnail does not always apply.
     */
    private fun uprightThumbnail(uri: android.net.Uri, size: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= size) sample *= 2
        val bitmap = contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val orientation = contentResolver.openInputStream(uri)?.use {
            androidx.exifinterface.media.ExifInterface(it).getAttributeInt(
                androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
            )
        } ?: androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
        val m = android.graphics.Matrix()
        when (orientation) {
            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            androidx.exifinterface.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            androidx.exifinterface.media.ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            androidx.exifinterface.media.ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            androidx.exifinterface.media.ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true).also {
            if (it !== bitmap) bitmap.recycle()
        }
    }

    /** Opens the last photo or video in the phone's gallery app. */
    private fun openLastShotInGallery() {
        val uri = latestCapturedUri ?: latestGalleryUri
        if (uri == null) {
            showHint("No photo in gallery yet")
            return
        }
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, contentResolver.getType(uri) ?: "image/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(view) }.onFailure { showHint("No gallery app to open this") }
    }

    private fun setupListeners() {
        // Bottom-left Settings button toggles the Quick Settings Sheet
        binding.btnBottomSettings.setOnClickListener {
            toggleQuickSettings()
        }

        setupSettingsSheetDrag()

        // Tapping viewfinder when Quick Settings is open closes it
        binding.viewFinder.setOnClickListener {
            if (binding.quickSettingsContainer.visibility == View.VISIBLE) {
                toggleQuickSettings()
            }
        }

        // "More settings" floating pill button opens full SettingsActivity
        // Quick Settings - Row 1: More light
        binding.btnQsLightNone.setOnClickListener {
            moreLightMode = 0
            setQuickFlash(ImageCapture.FLASH_MODE_OFF)
        }
        binding.btnQsLightNight.setOnClickListener {
            moreLightMode = 1
            setQuickFlash(ImageCapture.FLASH_MODE_AUTO)
        }
        binding.btnQsLightFlash.setOnClickListener {
            moreLightMode = 2
            setQuickFlash(ImageCapture.FLASH_MODE_ON)
        }

        // Quick Settings (Pro tab): RAW + JPEG
        binding.btnQsRes12.setOnClickListener { setFrontFullRes(false) }
        binding.btnQsRes108.setOnClickListener { setFrontFullRes(true) }
        binding.btnQsRawOff.setOnClickListener { setQuickRaw(false) }
        binding.btnQsRawOn.setOnClickListener { setQuickRaw(true) }

        // Quick Settings - Row 3: HDR+ (Off / Auto / Enhanced)
        binding.btnQsTopShotOff.setOnClickListener {
            setHdrMode(0)
        }
        binding.btnQsTopShotAuto.setOnClickListener {
            setHdrMode(1)
        }
        binding.btnQsTopShotOn.setOnClickListener {
            setHdrMode(2)
        }

        // Quick Settings: Mirror (per camera) and Motion photo
        binding.btnQsMirrorOff.setOnClickListener { setMirror(false) }
        binding.btnQsMirrorOn.setOnClickListener { setMirror(true) }
        binding.btnQsVideoMirrorOff.setOnClickListener { setMirror(false) }
        binding.btnQsVideoMirrorOn.setOnClickListener { setMirror(true) }
        binding.btnQsMotionOff.setOnClickListener { setMotionPhoto(false) }
        binding.btnQsMotionOn.setOnClickListener { setMotionPhoto(true) }

        // While recording: pause/resume and photo snapshot
        binding.btnVideoPause.setOnClickListener { toggleVideoPause() }
        binding.btnVideoSnapshot.setOnClickListener { takeVideoSnapshot() }

        binding.tvQrResult.setOnClickListener { openQrResult() }

        // Quick Settings - Row 4: Timer
        binding.btnQsTimerOff.setOnClickListener {
            timerDuration = 0
            updateQuickSettingsUi()
        }
        binding.btnQsTimer3s.setOnClickListener {
            timerDuration = 3
            updateQuickSettingsUi()
        }
        binding.btnQsTimer10s.setOnClickListener {
            timerDuration = 10
            updateQuickSettingsUi()
        }

        // Quick Settings - Row 6: Grid Type (Off / 3x3 / Golden Ratio)
        binding.btnQsGridOff.setOnClickListener {
            setGridType(0)
        }
        binding.btnQsGrid3x3.setOnClickListener {
            setGridType(1)
        }
        binding.btnQsGridGolden.setOnClickListener {
            setGridType(2)
        }

        // MGC 9.7 Segmented Tabs: [ General | Pro ]
        binding.tabQsGeneral.setOnClickListener {
            isProTabActive = false
            binding.quickSettingsSheet.minimumHeight = binding.quickSettingsSheet.height
            setQsTab(binding.tabQsGeneral, true)
            setQsTab(binding.tabQsPro, false)

            binding.containerQsGeneral.visibility = View.VISIBLE
            binding.containerQsPro.visibility = View.GONE
        }

        binding.tabQsPro.setOnClickListener {
            isProTabActive = true
            binding.quickSettingsSheet.minimumHeight = binding.quickSettingsSheet.height
            setQsTab(binding.tabQsPro, true)
            setQsTab(binding.tabQsGeneral, false)

            binding.containerQsGeneral.visibility = View.GONE
            binding.containerQsPro.visibility = View.VISIBLE
        }

        // Pro Tab: Lens Selection (Auto vs Manual)
        binding.btnQsLensAuto.setOnClickListener {
            binding.btnQsLensAuto.setBackgroundResource(R.drawable.bg_segmented_pill_active)
            binding.btnQsLensAuto.setTextColor(ContextCompat.getColor(this, R.color.gc_toggle_icon_active))
            binding.btnQsLensManual.setBackgroundResource(R.drawable.bg_qs_btn_inactive)
            binding.btnQsLensManual.setTextColor(ContextCompat.getColor(this, R.color.gc_icon))
            binding.tvQsLensSubtitle.text = "Auto"
        }

        binding.btnQsLensManual.setOnClickListener {
            binding.btnQsLensManual.setBackgroundResource(R.drawable.bg_segmented_pill_active)
            binding.btnQsLensManual.setTextColor(ContextCompat.getColor(this, R.color.gc_toggle_icon_active))
            binding.btnQsLensAuto.setBackgroundResource(R.drawable.bg_qs_btn_inactive)
            binding.btnQsLensAuto.setTextColor(ContextCompat.getColor(this, R.color.gc_icon))
            binding.tvQsLensSubtitle.text = "Manual"
        }

        // Quick Settings - Row 4: Aspect Ratio
        binding.btnQsRatio43.setOnClickListener {
            cameraManager.aspectRatio = AspectRatio.RATIO_4_3
            applyPreviewScale()
            cameraManager.bindCameraUseCases()
            updateQuickSettingsUi()
        }
        binding.btnQsRatio169.setOnClickListener {
            cameraManager.aspectRatio = AspectRatio.RATIO_16_9
            applyPreviewScale()
            cameraManager.bindCameraUseCases()
            updateQuickSettingsUi()
        }

        // Quick Settings - Row 6: Google AWB
        binding.btnQsAwbOff.setOnClickListener {
            cameraManager.isGoogleAwbEnabled = false
            cameraManager.bindCameraUseCases()
            updateQuickSettingsUi()
        }
        binding.btnQsAwbOn.setOnClickListener {
            cameraManager.isGoogleAwbEnabled = true
            cameraManager.bindCameraUseCases()
            updateQuickSettingsUi()
        }

        // Quick Settings - Floating "More settings" Pill
        binding.btnMoreSettings.setOnClickListener {
            binding.quickSettingsContainer.visibility = View.GONE
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Quick Settings - Video Mode Listeners
        binding.btnQsSlowOff.setOnClickListener { setSlowMotion(null) }
        binding.btnQsSlow4x.setOnClickListener { setSlowMotion(com.motorola.pixelcamera.core.SlowMotionCamera.Mode.X4) }
        binding.btnQsSlow8x.setOnClickListener { setSlowMotion(com.motorola.pixelcamera.core.SlowMotionCamera.Mode.X8) }
        binding.btnQsPreset1080p30.setOnClickListener {
            if (cameraManager.isRecordingVideo()) return@setOnClickListener
            cameraManager.setVideoProfile(CameraManager.VideoResolution.FHD, CameraManager.VideoFps.FPS_30)
            prefs.edit().putString(SettingsActivity.KEY_VIDEO_QUALITY, "1080p")
                .putInt(SettingsActivity.KEY_VIDEO_FPS, 30).apply()
            updateVideoQsUi()
            updateVideoBadgeText()
            updateStorageBadge()
        }

        binding.btnQsPreset1080p60.setOnClickListener {
            if (cameraManager.isRecordingVideo()) return@setOnClickListener
            cameraManager.setVideoProfile(CameraManager.VideoResolution.FHD, CameraManager.VideoFps.FPS_60)
            prefs.edit().putString(SettingsActivity.KEY_VIDEO_QUALITY, "1080p")
                .putInt(SettingsActivity.KEY_VIDEO_FPS, 60).apply()
            updateVideoQsUi()
            updateVideoBadgeText()
            updateStorageBadge()
        }

        binding.btnQsPreset4k30.setOnClickListener {
            if (cameraManager.isRecordingVideo()) return@setOnClickListener
            if (cameraManager.isFrontCamera()) {
                showHint("4K is on the rear camera only")
                return@setOnClickListener
            }
            cameraManager.setVideoProfile(CameraManager.VideoResolution.UHD, CameraManager.VideoFps.FPS_30)
            prefs.edit().putString(SettingsActivity.KEY_VIDEO_QUALITY, "4k")
                .putInt(SettingsActivity.KEY_VIDEO_FPS, 30).apply()
            updateVideoQsUi()
            updateVideoBadgeText()
            updateStorageBadge()
        }

        binding.btnQsVideoResFhd.setOnClickListener {
            if (cameraManager.isRecordingVideo()) return@setOnClickListener
            cameraManager.setVideoResolution(CameraManager.VideoResolution.FHD)
            prefs.edit().putString(SettingsActivity.KEY_VIDEO_QUALITY, "1080p").apply()
            updateVideoQsUi()
            updateVideoBadgeText()
            updateStorageBadge()
        }

        binding.btnQsVideoRes4k.setOnClickListener {
            if (cameraManager.isRecordingVideo()) return@setOnClickListener
            if (cameraManager.isFrontCamera()) {
                showHint("4K is on the rear camera only")
                return@setOnClickListener
            }
            cameraManager.setVideoResolution(CameraManager.VideoResolution.UHD)
            prefs.edit().putString(SettingsActivity.KEY_VIDEO_QUALITY, "4k")
                .putInt(SettingsActivity.KEY_VIDEO_FPS, 30).apply()
            updateVideoQsUi()
            updateVideoBadgeText()
            updateStorageBadge()
        }

        binding.btnQsVideoFps30.setOnClickListener {
            if (cameraManager.isRecordingVideo()) return@setOnClickListener
            cameraManager.setVideoFps(CameraManager.VideoFps.FPS_30)
            prefs.edit().putInt(SettingsActivity.KEY_VIDEO_FPS, 30).apply()
            updateVideoQsUi()
            updateVideoBadgeText()
        }

        binding.btnQsVideoFps60.setOnClickListener {
            if (cameraManager.isRecordingVideo()) return@setOnClickListener
            if (cameraManager.currentVideoResolution == CameraManager.VideoResolution.UHD) {
                // Dimensity 800U supports 60fps at 1080p, automatically switch to 1080p
                cameraManager.setVideoProfile(CameraManager.VideoResolution.FHD, CameraManager.VideoFps.FPS_60)
                prefs.edit().putString(SettingsActivity.KEY_VIDEO_QUALITY, "1080p")
                    .putInt(SettingsActivity.KEY_VIDEO_FPS, 60).apply()
                showHint("60 fps needs 1080p · switched")
            } else {
                cameraManager.setVideoFps(CameraManager.VideoFps.FPS_60)
                prefs.edit().putInt(SettingsActivity.KEY_VIDEO_FPS, 60).apply()
            }
            updateVideoQsUi()
            updateVideoBadgeText()
        }

        binding.btnQsVideoTorchOff.setOnClickListener {
            cameraManager.flashMode = ImageCapture.FLASH_MODE_OFF
            updateVideoQsUi()
        }

        binding.btnQsVideoTorchOn.setOnClickListener {
            cameraManager.flashMode = ImageCapture.FLASH_MODE_ON
            updateVideoQsUi()
        }

        binding.btnQsLowLightVideoOff.setOnClickListener { setLowLightVideo(false) }
        binding.btnQsLowLightVideoOn.setOnClickListener { setLowLightVideo(true) }

        binding.btnQsAudioZoomOff.setOnClickListener {
            cameraManager.audioZoomEnabled = false
            updateVideoQsUi()
        }

        binding.btnQsAudioZoomOn.setOnClickListener {
            cameraManager.audioZoomEnabled = true
            updateVideoQsUi()
        }

        binding.btnQsVideoGridOff.setOnClickListener {
            setGridType(0)
            updateVideoQsUi()
        }

        binding.btnQsVideoGrid3x3.setOnClickListener {
            setGridType(1)
            updateVideoQsUi()
        }

        binding.btnQsVideoGridGolden.setOnClickListener {
            setGridType(2)
            updateVideoQsUi()
        }

        // Flash Button Quick Toggle in Top Bar (Off -> Auto -> On -> Off)
        binding.btnFlash.setOnClickListener {
            val nextFlash = when (cameraManager.flashMode) {
                ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_AUTO
                ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
                else -> ImageCapture.FLASH_MODE_OFF
            }
            setQuickFlash(nextFlash)
            moreLightMode = when (nextFlash) {
                ImageCapture.FLASH_MODE_ON -> 2
                ImageCapture.FLASH_MODE_AUTO -> 1
                else -> 0
            }
            updateQuickSettingsUi()
        }

        // Tier 3: Photo / Video Toggle Pill
        binding.btnToggleCamera.setOnClickListener {
            switchToPhotoMode()
        }
        binding.btnToggleVideo.setOnClickListener {
            switchToVideoMode()
        }

        // Tier 3: Pro Controls Button (ic_tune)
        binding.btnProControls.setOnClickListener { onSlidersButton() }

        // Tier 1: Shutter Button
        binding.btnShutter.setOnClickListener {
            onShutterClicked()
        }
        // Holding the shutter in Photo mode shoots a burst until it is released
        binding.btnShutter.setOnLongClickListener {
            startHoldBurst()
        }
        binding.btnShutter.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                shutterHeld = false
            } else if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                shutterHeld = true
            }
            false
        }

        // Tier 1: Camera Switch (Front <-> Rear)
        binding.btnSwitchCamera.setOnClickListener {
            flipCameraWithAnimation()
        }

        // MGC 9.7 Dual Zoom Selectors (Upper Physical Lenses + Lower Multipliers)
        binding.btnZoomUltrawide.setOnClickListener {
            if (!cameraManager.isUltrawideAvailable()) {
                showHint("Ultrawide is not available in this build")
                return@setOnClickListener
            }
            cameraManager.switchToLens(CameraManager.CAMERA_ULTRAWIDE, 1.0f)
            updateDualZoomUi("0.6", "1×")
            binding.tvSensorInfo.text = "8MP ULTRAWIDE"
        }

        binding.btnLens10.setOnClickListener {
            cameraManager.switchToLens(CameraManager.CAMERA_MAIN, 1.0f)
            updateDualZoomUi("1.0", "1×")
            binding.tvSensorInfo.text = MAIN_SENSOR_LABEL
        }

        binding.btnLens20.setOnClickListener {
            cameraManager.switchToLens(CameraManager.CAMERA_MAIN, 2.0f)
            updateDualZoomUi("2.0", "2")
            binding.tvSensorInfo.text = "2X ZOOM"
        }

        binding.btnZoom1x.setOnClickListener {
            cameraManager.switchToLens(CameraManager.CAMERA_MAIN, 1.0f)
            updateDualZoomUi("1.0", "1×")
            binding.tvSensorInfo.text = MAIN_SENSOR_LABEL
        }

        binding.btnZoom2x.setOnClickListener {
            cameraManager.switchToLens(CameraManager.CAMERA_MAIN, 2.0f)
            updateDualZoomUi("2.0", "2")
            binding.tvSensorInfo.text = "2X ZOOM"
        }

        binding.btnZoom5x.setOnClickListener {
            cameraManager.switchToLens(CameraManager.CAMERA_MAIN, 5.0f)
            updateDualZoomUi("2.0", "5")
            binding.tvSensorInfo.text = "5X ZOOM"
        }

        // Tier 2: Mode Ribbon / Carousel
        binding.modeLongExposure.setOnClickListener {
            setMode(CameraMode.LONG_EXPOSURE, binding.modeLongExposure)
        }
        binding.modePortrait.setOnClickListener { setMode(CameraMode.PORTRAIT, binding.modePortrait) }
        binding.modeCamera.setOnClickListener { setMode(CameraMode.CAMERA, binding.modeCamera) }
        binding.modeNightSight.setOnClickListener { setMode(CameraMode.NIGHT_SIGHT, binding.modeNightSight) }
        binding.modePanorama.setOnClickListener {
            setMode(CameraMode.PANORAMA, binding.modePanorama)
        }
        binding.modePro.setOnClickListener { setMode(CameraMode.PRO, binding.modePro) }
        binding.modeVideo.setOnClickListener { chooseCarouselItem(binding.modeVideo) }
        binding.modeSlowMotion.setOnClickListener { chooseCarouselItem(binding.modeSlowMotion) }
        binding.chipBottomRight.setOnClickListener { onBottomRightChip() }
        setupModeCarousel()

        // Portrait Aperture Slider (f/1.4 - f/16)
        binding.sbAperture.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                portraitBlurProgress = progress
                cameraManager.portraitPreviewBlur = portraitBlurStrength()
                val fStops = arrayOf("f / 1.4", "f / 1.8", "f / 2.2", "f / 2.8", "f / 4.0", "f / 5.6", "f / 8.0", "f / 11", "f / 16")
                val index = ((100 - progress) / 100f * (fStops.size - 1)).toInt().coerceIn(0, fStops.size - 1)
                binding.tvApertureLabel.text = fStops[index]
                binding.tvBokehPercent.text = "Blur $progress%"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Pro Manual Controls Tabs [ ISO | S | FOCUS | WB | EV ]
        binding.tabProIso.setOnClickListener { selectProTab(ProTab.ISO) }
        binding.tabProShutter.setOnClickListener { selectProTab(ProTab.SHUTTER) }
        binding.tabProFocus.setOnClickListener { selectProTab(ProTab.FOCUS) }
        binding.tabProWb.setOnClickListener { selectProTab(ProTab.WB) }
        binding.tabProEv.setOnClickListener { selectProTab(ProTab.EV) }

        binding.sbProSlider.onUserChange = { position -> setProFromRuler(position) }
        binding.tvRulerMin.setOnClickListener { binding.sbProSlider.nudge(-1) }
        binding.tvRulerMax.setOnClickListener { binding.sbProSlider.nudge(1) }
        binding.btnProReset.setOnClickListener {
            // Automatic -> manual where the ruler stands; manual -> back to automatic.
            if (isProAuto(activeProTab)) setProFromRuler(binding.sbProSlider.progress) else resetProControl(activeProTab)
            selectProTab(activeProTab)
        }
        binding.btnProResetAll.setOnClickListener {
            ProTab.values().forEach { resetProControl(it) }
            selectProTab(activeProTab)
        }
        setupProPanelClose()

        // Tap to Focus with Interactive Brightness Exposure Slider & Gesture Zoom/Flip
        binding.viewFinder.setOnTouchListener { _, event ->
            if (binding.quickSettingsContainer.visibility == View.VISIBLE) {
                if (event.action == MotionEvent.ACTION_DOWN) {
                    toggleQuickSettings()
                }
                return@setOnTouchListener true
            }

            scaleGestureDetector.onTouchEvent(event)
            gestureDetector.onTouchEvent(event)

            if (scaleGestureDetector.isInProgress || event.pointerCount > 1) {
                isDraggingExposure = false
                return@setOnTouchListener true
            }

            val density = resources.displayMetrics.density

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // Check if reticle is currently visible and touch is on/near the exposure slider track
                    if (binding.focusReticleContainer.visibility == View.VISIBLE) {
                        val trackLocation = IntArray(2)
                        binding.exposureSliderTrack.getLocationOnScreen(trackLocation)
                        val touchX = event.rawX.toInt()
                        val touchY = event.rawY.toInt()

                        val hitPaddingX = (28 * density).toInt()
                        val hitPaddingY = (16 * density).toInt()
                        val trackRect = Rect(
                            trackLocation[0] - hitPaddingX,
                            trackLocation[1] - hitPaddingY,
                            trackLocation[0] + binding.exposureSliderTrack.width + hitPaddingX,
                            trackLocation[1] + binding.exposureSliderTrack.height + hitPaddingY
                        )

                        if (trackRect.contains(touchX, touchY)) {
                            // User touched directly on the slider!
                            isDraggingExposure = true
                            dragStartY = event.rawY
                            initialEvIndex = currentEvIndex
                            reticleAutoHideJob?.cancel()
                            showExposureEvBadge()
                            return@setOnTouchListener true
                        }
                    }

                    // Tapping anywhere on viewfinder focuses and displays the reticle + slider
                    isDraggingExposure = true
                    dragStartY = event.rawY
                    initialEvIndex = currentEvIndex
                    reticleAutoHideJob?.cancel()

                    val factory = binding.viewFinder.meteringPointFactory
                    val point = factory.createPoint(event.x, event.y)
                    cameraManager.focusAt(point)
                    setFocusLocked(false)
                    lastTapFocus = viewToImagePoint(event.x, event.y)
                    showFocusReticleAndSlider(event.x, event.y)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (isDraggingExposure && binding.focusReticleContainer.visibility == View.VISIBLE) {
                        val dy = dragStartY - event.rawY // Dragging up = positive dy (brighter)

                        if (abs(dy) > 4 * density) {
                            reticleAutoHideJob?.cancel()

                            val range = cameraManager.getExposureCompensationRange()
                            val totalSteps = (range.upper - range.lower).coerceAtLeast(1)

                            // 160dp total drag distance spans the entire EV range from min to max
                            val dragDistancePerStep = (160f * density) / totalSteps
                            val evStepDelta = (dy / dragDistancePerStep).toInt()
                            val targetEv = (initialEvIndex + evStepDelta).coerceIn(range.lower, range.upper)

                            if (targetEv != currentEvIndex) {
                                currentEvIndex = targetEv
                                cameraManager.setExposureCompensation(targetEv)
                                updateExposureThumbPosition(targetEv, range)
                                showExposureEvBadge()
                            }
                        }
                        true
                    } else {
                        false
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isDraggingExposure) {
                        isDraggingExposure = false
                        // Fade out the EV numeric badge after 1s
                        binding.tvExposureEvBadge.animate()
                            .alpha(0f)
                            .setStartDelay(1000)
                            .setDuration(300)
                            .withEndAction {
                                binding.tvExposureEvBadge.visibility = View.GONE
                                binding.tvExposureEvBadge.alpha = 1f
                            }
                            .start()

                        // Keep the reticle and slider visible for 3.5s before gently fading out
                        scheduleReticleAutoHide(3500L)
                    }
                    true
                }

                else -> false
            }
        }

        // Gallery Thumbnail Click
        binding.ivGalleryThumbnail.setOnClickListener {
            openLastShotInGallery()
        }

    }

    private fun toggleQuickSettings() {
        val sheet = binding.quickSettingsContainer
        val travel = (sheet.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels / 2).toFloat()
        if (sheet.visibility == View.VISIBLE) {
            sheet.animate()
                .translationY(travel)
                .setDuration(200)
                .withEndAction {
                    sheet.visibility = View.GONE
                    sheet.translationY = 0f
                }
                .start()
        } else {
            updateQuickSettingsUi()
            binding.quickSettingsSheet.minimumHeight = 0
            sheet.alpha = 1f
            sheet.translationY = travel
            sheet.visibility = View.VISIBLE
            sheet.animate()
                .translationY(0f)
                .setDuration(250)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        }
    }

    /** Google Camera's settings tabs: two separate pills, the chosen one light blue. */
    private fun setQsTab(tab: TextView, active: Boolean) {
        tab.setBackgroundResource(if (active) R.drawable.bg_gc_tab_active else R.drawable.bg_gc_tab_inactive)
        tab.setTextColor(ContextCompat.getColor(this, if (active) R.color.gc_toggle_icon_active else R.color.gc_icon))
    }

    /** Dragging the sheet's top part (handle, title) down closes it, as a bottom sheet does. */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun setupSettingsSheetDrag() {
        val sheet = binding.quickSettingsContainer
        val closeAfter = 80 * resources.displayMetrics.density
        var downY = 0f
        binding.quickSettingsSheet.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downY = e.rawY; true }
                MotionEvent.ACTION_MOVE -> { sheet.translationY = (e.rawY - downY).coerceAtLeast(0f); true }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (sheet.translationY > closeAfter) toggleQuickSettings()
                    else sheet.animate().translationY(0f).setDuration(150).start()
                    true
                }
                else -> false
            }
        }
    }

    private fun selectProTab(tab: ProTab) {
        activeProTab = tab
        val icons = mapOf(
            ProTab.FOCUS to binding.icProFocus, ProTab.SHUTTER to binding.icProShutter,
            ProTab.ISO to binding.icProIso, ProTab.WB to binding.icProWb, ProTab.EV to binding.icProEv
        )
        for ((t, icon) in icons) {
            val on = t == tab
            icon.setBackgroundResource(if (on) R.drawable.bg_gc_pro_circle_active else R.drawable.bg_gc_pro_circle)
            ImageViewCompat.setImageTintList(
                icon, ContextCompat.getColorStateList(this, if (on) R.color.gc_toggle_icon_active else R.color.gc_icon)
            )
        }
        val ruler = binding.sbProSlider
        val evRange = cameraManager.getExposureCompensationRange()
        when (tab) {
            ProTab.FOCUS -> {
                ruler.max = 100
                ruler.progress = if (proFocusProgress == 0) 80 else 100 - proFocusProgress
                setRulerEnds(R.drawable.ic_macro, R.drawable.ic_mountain)
            }
            ProTab.SHUTTER -> {
                ruler.max = shutterLabels.size - 2
                ruler.progress = if (proShutterIndex == 0) nearestShutterIndex(cameraManager.liveExposureNs) - 1 else proShutterIndex - 1
                setRulerEnds(shutterLabels[1].removeSuffix("s"), shutterLabels.last().removeSuffix("s"))
            }
            ProTab.ISO -> {
                ruler.max = isoValues.size - 2
                ruler.progress = if (proIsoIndex == 0) nearestIsoIndex(cameraManager.liveIso) - 1 else proIsoIndex - 1
                setRulerEnds(isoValues[1].toString(), isoValues.last().toString())
            }
            ProTab.WB -> {
                ruler.max = wbLabels.size - 2
                ruler.progress = if (proWbIndex == 0) 2 else proWbIndex - 1
                setRulerEnds(wbLabels[1].substringBefore(' '), wbLabels.last().substringBefore(' '))
            }
            ProTab.EV -> {
                ruler.max = (evRange.upper - evRange.lower).coerceAtLeast(1)
                ruler.progress = currentEvIndex - evRange.lower
                val step = cameraManager.getExposureCompensationStep()
                setRulerEnds(String.format(java.util.Locale.US, "%+.0f", evRange.lower * step), String.format(java.util.Locale.US, "%+.0f", evRange.upper * step))
            }
        }
        val auto = isProAuto(tab)
        binding.btnProReset.setBackgroundResource(if (auto) R.drawable.bg_gc_pro_circle_active else R.drawable.bg_gc_pro_btn)
        binding.btnProReset.setTextColor(ContextCompat.getColor(this, if (auto) R.color.gc_toggle_icon_active else R.color.gc_icon))
        updateProInfo()
    }

    private fun setRulerEnds(minIcon: Int, maxIcon: Int) {
        binding.tvRulerMin.text = ""; binding.tvRulerMax.text = ""
        binding.tvRulerMin.setCompoundDrawablesRelativeWithIntrinsicBounds(0, minIcon, 0, 0)
        binding.tvRulerMax.setCompoundDrawablesRelativeWithIntrinsicBounds(0, maxIcon, 0, 0)
        val tint = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this, R.color.gc_toggle_active))
        androidx.core.widget.TextViewCompat.setCompoundDrawableTintList(binding.tvRulerMin, tint)
        androidx.core.widget.TextViewCompat.setCompoundDrawableTintList(binding.tvRulerMax, tint)
        binding.tvRulerMin.setPadding(0, (8 * resources.displayMetrics.density).toInt(), 0, 0)
        binding.tvRulerMax.setPadding(0, (8 * resources.displayMetrics.density).toInt(), 0, 0)
    }

    private fun setRulerEnds(min: String, max: String) {
        binding.tvRulerMin.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0)
        binding.tvRulerMax.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0)
        binding.tvRulerMin.setPadding(0, 0, 0, 0); binding.tvRulerMax.setPadding(0, 0, 0, 0)
        binding.tvRulerMin.text = min; binding.tvRulerMax.text = max
    }

    private fun isProAuto(tab: ProTab): Boolean = when (tab) {
        ProTab.FOCUS -> proFocusProgress == 0
        ProTab.SHUTTER -> proShutterIndex == 0
        ProTab.ISO -> proIsoIndex == 0
        ProTab.WB -> proWbIndex == 0
        ProTab.EV -> currentEvIndex == 0
    }

    /** The ruler moved to [position]: set the active control by hand. */
    private fun setProFromRuler(position: Int) {
        when (activeProTab) {
            ProTab.FOCUS -> {
                proFocusProgress = (100 - position).coerceIn(1, 100)
                cameraManager.setManualFocus(proFocusProgress / 100f * 10f)
            }
            ProTab.SHUTTER -> {
                proShutterIndex = (position + 1).coerceIn(1, shutterNanos.size - 1)
                cameraManager.setManualShutter(shutterNanos[proShutterIndex])
            }
            ProTab.ISO -> {
                proIsoIndex = (position + 1).coerceIn(1, isoValues.size - 1)
                cameraManager.setManualIso(isoValues[proIsoIndex])
            }
            ProTab.WB -> {
                proWbIndex = (position + 1).coerceIn(1, wbModes.size - 1)
                cameraManager.setManualWb(wbModes[proWbIndex])
            }
            ProTab.EV -> {
                currentEvIndex = cameraManager.getExposureCompensationRange().lower + position
                cameraManager.setExposureCompensation(currentEvIndex)
            }
        }
        val auto = isProAuto(activeProTab)
        binding.btnProReset.setBackgroundResource(if (auto) R.drawable.bg_gc_pro_circle_active else R.drawable.bg_gc_pro_btn)
        binding.btnProReset.setTextColor(ContextCompat.getColor(this, if (auto) R.color.gc_toggle_icon_active else R.color.gc_icon))
        updateProInfo()
    }

    private fun resetProControl(tab: ProTab) {
        when (tab) {
            ProTab.FOCUS -> { proFocusProgress = 0; cameraManager.setManualFocus(-1f) }
            ProTab.SHUTTER -> { proShutterIndex = 0; cameraManager.setManualShutter(0L) }
            ProTab.ISO -> { proIsoIndex = 0; cameraManager.setManualIso(0) }
            ProTab.WB -> { proWbIndex = 0; cameraManager.setManualWb(CaptureRequest.CONTROL_AWB_MODE_AUTO) }
            ProTab.EV -> { currentEvIndex = 0; cameraManager.setExposureCompensation(0) }
        }
    }

    private fun nearestIsoIndex(iso: Int): Int =
        if (iso <= 0) 1 else (1 until isoValues.size).minByOrNull { kotlin.math.abs(kotlin.math.ln(isoValues[it].toDouble() / iso)) } ?: 1

    private fun nearestShutterIndex(ns: Long): Int =
        if (ns <= 0) 7 else (1 until shutterNanos.size).minByOrNull { kotlin.math.abs(kotlin.math.ln(shutterNanos[it].toDouble() / ns)) } ?: 7

    private fun formatExposure(ns: Long): String = when {
        ns <= 0 -> "--"
        ns >= 500_000_000L -> String.format(java.util.Locale.US, "%.1fs", ns / 1e9)
        else -> "1/${(1e9 / ns).roundToInt()}s"
    }

    /** The row across the top: what each control is set to, Ⓐ where the camera chooses. */
    private fun updateProInfo() {
        binding.tvProInfoFocus.text = if (proFocusProgress == 0) "Auto focus" else {
            val metres = 10f / proFocusProgress
            if (metres >= 5f) "Focus ∞" else String.format(java.util.Locale.US, "Focus %.2g m", metres)
        }
        binding.tvProInfoShutter.text = if (proShutterIndex == 0) "Ⓐ ${formatExposure(cameraManager.liveExposureNs)}" else shutterLabels[proShutterIndex]
        binding.tvProInfoIso.text = if (proIsoIndex == 0) {
            val live = cameraManager.liveIso
            if (live > 0) "Ⓐ ISO $live" else "Ⓐ ISO"
        } else "ISO ${isoValues[proIsoIndex]}"
        binding.tvProInfoWb.text = if (proWbIndex == 0) "Auto WB" else wbLabels[proWbIndex].substringBefore(' ')
        binding.tvProInfoEv.visibility = if (currentEvIndex == 0) View.GONE else View.VISIBLE
        val ev = currentEvIndex * cameraManager.getExposureCompensationStep()
        binding.tvProInfoEv.text = String.format(java.util.Locale.US, "%+.1f EV", ev)
    }

    private val proInfoTicker = object : Runnable {
        override fun run() {
            if (binding.proControlPanel.visibility != View.VISIBLE) return
            updateProInfo()
            binding.root.postDelayed(this, 500)
        }
    }

    /** Google Camera's manual controls replace the mode row and zoom; the shutter row stays. */
    private fun showProPanel(show: Boolean) {
        val v = if (show) View.VISIBLE else View.GONE
        binding.proControlPanel.visibility = v
        binding.proInfoRow.visibility = v
        binding.proSliderRow.visibility = v
        binding.modeCarousel.visibility = if (show) View.INVISIBLE else View.VISIBLE
        binding.bottomNavBar.visibility = if (show) View.INVISIBLE else View.VISIBLE
        binding.root.removeCallbacks(proInfoTicker)
        if (show) {
            selectProTab(activeProTab)
            binding.root.post(proInfoTicker)
        }
    }

    /** Tapping the handle or dragging the bar down leaves manual controls. */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun setupProPanelClose() {
        val leave = { if (currentMode == CameraMode.PRO) setMode(CameraMode.CAMERA, binding.modeCamera) }
        binding.proPanelHandle.setOnClickListener { leave() }
        var downY = 0f
        binding.proControlPanel.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> downY = e.rawY
                MotionEvent.ACTION_UP -> if (e.rawY - downY > 60 * resources.displayMetrics.density) leave()
            }
            true
        }
    }

    private fun flipCameraWithAnimation() {
        if (slowMode != null) {
            showHint("Slow motion uses the rear camera")
            return
        }
        if (isCapturing || timerJob?.isActive == true || cameraManager.isRecordingVideo() || cameraManager.isCapturingPanorama()) return
        binding.quickSettingsContainer.visibility = View.GONE
        hidePortraitPreview()
        binding.btnSwitchCamera.animate().rotationBy(180f).setDuration(300).start()
        cameraManager.switchCamera()
        val isFront = cameraManager.isFrontCamera()
        updateZoomPillVisibility()
        updateSensorLabel()
        if (!isFront) {
            selectZoomPill(binding.btnZoom1x)
        }
        checkSelfieRingLight()
        lastTapFocus = null
        setFocusLocked(false)
        updateQuickSettingsUi()
    }

    /** 16:9 photos and video are letterboxed so the viewfinder shows exactly what is saved. */
    private fun applyPreviewScale() {
        // Video fills the viewfinder: the frame is a little taller than the screen area, so about
        // 4% at the top and bottom of the recording lies outside the preview. A 16:9 photo is
        // shown whole, because a photo's framing has to be exact.
        val wide = cameraManager.aspectRatio == AspectRatio.RATIO_16_9 && currentMode != CameraMode.VIDEO
        // Google Camera: every frame starts 71dp from the top; 16:9 and video run on down behind the controls.
        val tall = wide || currentMode == CameraMode.VIDEO
        (binding.viewFinderCard.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams).let { lp ->
            val ratio = if (tall) "9:16" else "3:4"
            val top = resources.getDimensionPixelSize(R.dimen.gc_preview_top)
            if (lp.dimensionRatio != ratio || lp.topMargin != top) {
                lp.dimensionRatio = ratio
                lp.topMargin = top
                binding.viewFinderCard.layoutParams = lp
            }
        }
        updateMiniBadge()
        binding.viewFinder.scaleType = if (wide) {
            androidx.camera.view.PreviewView.ScaleType.FIT_CENTER
        } else {
            androidx.camera.view.PreviewView.ScaleType.FILL_CENTER
        }
    }

    private fun updateZoomPillVisibility() {
        // Slow motion runs its own session at a fixed 1x.
        val hide = cameraManager.isFrontCamera() || currentMode == CameraMode.PANORAMA || currentMode == CameraMode.PRO || slowMode != null
        binding.zoomPillContainer.visibility = if (hide) View.GONE else View.VISIBLE
    }

    private fun updateZoomUiForRatio(zoom: Float) {
        when {
            zoom < 1.4f -> updateDualZoomUi("1.0", "1×")
            zoom in 1.4f..3.5f -> updateDualZoomUi("2.0", "2")
            else -> updateDualZoomUi("2.0", "5")
        }
        binding.tvSensorInfo.text = String.format(java.util.Locale.US, "%.1fX ZOOM", zoom)
    }

    private fun setGridType(mode: Int) {
        gridMode = mode
        prefs.edit().putInt(SettingsActivity.KEY_GRID_MODE, mode).apply()
        val activeBg = R.drawable.bg_qs_btn_active
        val inactiveBg = R.drawable.bg_qs_btn_inactive
        val activeTextColor = ContextCompat.getColor(this, R.color.gc_toggle_icon_active)
        val inactiveTextColor = ContextCompat.getColor(this, R.color.gc_icon)

        binding.btnQsGridOff.setBackgroundResource(if (mode == 0) activeBg else inactiveBg)
        binding.btnQsGridOff.setTextColor(if (mode == 0) activeTextColor else inactiveTextColor)

        binding.btnQsGrid3x3.setBackgroundResource(if (mode == 1) activeBg else inactiveBg)
        binding.btnQsGrid3x3.setTextColor(if (mode == 1) activeTextColor else inactiveTextColor)

        binding.btnQsGridGolden.setBackgroundResource(if (mode == 2) activeBg else inactiveBg)
        binding.btnQsGridGolden.setTextColor(if (mode == 2) activeTextColor else inactiveTextColor)

        binding.btnQsVideoGridOff.setBackgroundResource(if (mode == 0) activeBg else inactiveBg)
        binding.btnQsVideoGridOff.setTextColor(if (mode == 0) activeTextColor else inactiveTextColor)

        binding.btnQsVideoGrid3x3.setBackgroundResource(if (mode == 1) activeBg else inactiveBg)
        binding.btnQsVideoGrid3x3.setTextColor(if (mode == 1) activeTextColor else inactiveTextColor)

        binding.btnQsVideoGridGolden.setBackgroundResource(if (mode == 2) activeBg else inactiveBg)
        binding.btnQsVideoGridGolden.setTextColor(if (mode == 2) activeTextColor else inactiveTextColor)

        binding.tvQsVideoGridSubtitle.text = when (mode) {
            1 -> "3 × 3"
            2 -> "Golden Ratio"
            else -> "Off"
        }

        when (mode) {
            0 -> {
                binding.gridOverlay.visibility = View.GONE
                binding.tvQsGridSubtitle.text = "Off"
            }
            1 -> {
                binding.gridOverlay.visibility = View.VISIBLE
                binding.guideV1.setGuidelinePercent(0.333f)
                binding.guideV2.setGuidelinePercent(0.667f)
                binding.guideH1.setGuidelinePercent(0.333f)
                binding.guideH2.setGuidelinePercent(0.667f)
                binding.tvQsGridSubtitle.text = "3 × 3"
            }
            2 -> {
                binding.gridOverlay.visibility = View.VISIBLE
                binding.guideV1.setGuidelinePercent(0.382f)
                binding.guideV2.setGuidelinePercent(0.618f)
                binding.guideH1.setGuidelinePercent(0.382f)
                binding.guideH2.setGuidelinePercent(0.618f)
                binding.tvQsGridSubtitle.text = "Golden Ratio"
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_CAMERA -> {
                if (event?.repeatCount == 0) {
                    onShutterClicked()
                }
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun switchToPhotoMode() {
        if (currentMode == CameraMode.VIDEO) {
            if (cameraManager.isRecordingVideo()) {
                stopVideoRecordingUi()
                cameraManager.stopVideoRecording()
            }
            setMode(CameraMode.CAMERA, binding.modeCamera)
        }
        updatePhotoVideoToggle(isVideo = false)
        padCarousel()
    }

    private fun switchToVideoMode() {
        if (currentMode != CameraMode.VIDEO) {
            setMode(CameraMode.VIDEO, binding.modeVideo)
        }
        updatePhotoVideoToggle(isVideo = true)
        padCarousel()
    }

    /** Top-left badge, as Google Camera's minibar: the video format while in Video. */
    private fun updateMiniBadge() {
        val badge = binding.tvMiniBadge
        if (currentMode != CameraMode.VIDEO) {
            badge.visibility = View.GONE
            return
        }
        badge.text = when (cameraManager.currentVideoResolution) {
            CameraManager.VideoResolution.UHD -> "4K"
            else -> "FHD"
        }
        badge.visibility = View.VISIBLE
    }

    private fun updatePhotoVideoToggle(isVideo: Boolean) {
        if (isVideo) {
            binding.btnToggleVideo.setBackgroundResource(R.drawable.bg_toggle_item_active)
            ImageViewCompat.setImageTintList(binding.btnToggleVideo, ContextCompat.getColorStateList(this, R.color.gc_toggle_icon_active))

            binding.btnToggleCamera.background = null
            ImageViewCompat.setImageTintList(binding.btnToggleCamera, ContextCompat.getColorStateList(this, R.color.gc_icon))
        } else {
            binding.btnToggleCamera.setBackgroundResource(R.drawable.bg_toggle_item_active)
            ImageViewCompat.setImageTintList(binding.btnToggleCamera, ContextCompat.getColorStateList(this, R.color.gc_toggle_icon_active))

            binding.btnToggleVideo.background = null
            ImageViewCompat.setImageTintList(binding.btnToggleVideo, ContextCompat.getColorStateList(this, R.color.gc_icon))
        }
    }

    private fun setQuickFlash(mode: Int) {
        cameraManager.flashMode = mode
        prefs.edit().putInt(SettingsActivity.KEY_FLASH_MODE, mode).apply()
        updateFlashUi(mode)
        updateQuickSettingsUi()
        checkSelfieRingLight()
    }

    private fun checkSelfieRingLight() {
        val isFront = cameraManager.isFrontCamera()
        val shouldLight = isFront && (cameraManager.flashMode == ImageCapture.FLASH_MODE_ON)

        if (shouldLight) {
            if (!isSelfieWarmLightActive) {
                isSelfieWarmLightActive = true
                binding.overlaySelfieRing.visibility = View.VISIBLE
                setScreenBrightness(1.0f)
            }
        } else {
            if (isSelfieWarmLightActive) {
                isSelfieWarmLightActive = false
                binding.overlaySelfieRing.visibility = View.GONE
                setScreenBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
            }
        }
    }

    private fun setScreenBrightness(brightness: Float) {
        val lp = window.attributes
        lp.screenBrightness = brightness
        window.attributes = lp
    }

    private fun updateStorageBadge() {
        try {
            val stat = StatFs(Environment.getExternalStorageDirectory().path)
            val bytesAvailable = stat.availableBytes
            val gbAvailable = bytesAvailable / (1024.0 * 1024.0 * 1024.0)

            if (currentMode == CameraMode.VIDEO) {
                val mbPerMin = if (cameraManager.currentVideoResolution == CameraManager.VideoResolution.UHD) 380L * 1024 * 1024 else 130L * 1024 * 1024
                val minutesLeft = (bytesAvailable / mbPerMin).toInt()
                binding.tvStorageInfo.text = String.format(Locale.US, "%.1f GB • ~%d min video", gbAvailable, minutesLeft)
            } else {
                val photosLeft = (bytesAvailable / (4L * 1024 * 1024)).toInt()
                val photosFormatted = if (photosLeft >= 1000) String.format(Locale.US, "%,d", photosLeft) else photosLeft.toString()
                binding.tvStorageInfo.text = String.format(Locale.US, "%.1f GB • ~%s photos", gbAvailable, photosFormatted)
            }
        } catch (e: Exception) {
            binding.tvStorageInfo.text = "Storage OK"
        }
    }

    private fun setFrontFullRes(enabled: Boolean) {
        if (isModeChangeBlocked()) return
        cameraManager.fullResFront = enabled
        prefs.edit().putBoolean(SettingsActivity.KEY_FRONT_FULL_RES, enabled).apply()
        updateQuickSettingsUi()
        updateSensorLabel()
        if (enabled) showHint("32 MP selfies in good light · 8 MP when it is dim")
    }

    /** Sensor chip for the plain 1x view of whichever camera is active. */
    private fun updateSensorLabel() {
        binding.tvSensorInfo.text = when {
            !cameraManager.isFrontCamera() -> MAIN_SENSOR_LABEL
            cameraManager.isFullResActive(currentMode) -> "32MP FRONT"
            else -> FRONT_SENSOR_LABEL
        }
    }

    private fun setQuickRaw(enabled: Boolean) {
        if (isModeChangeBlocked()) return
        cameraManager.isRawEnabled = enabled
        prefs.edit().putBoolean(SettingsActivity.KEY_RAW_ENABLED, enabled).apply()
        cameraManager.bindCameraUseCases()
        updateQuickSettingsUi()
        if (enabled && !cameraManager.isRawActive()) {
            showHint(
                if (currentMode == CameraMode.CAMERA || currentMode == CameraMode.PRO) "RAW is not supported on this camera"
                else "RAW applies in Photo and Pro"
            )
        }
    }

    private fun setHdrMode(mode: Int) {
        hdrMode = mode
        cameraManager.hdrPlusMode = mode
        prefs.edit().putInt(SettingsActivity.KEY_HDR_MODE, mode).apply()
        updateQuickSettingsUi()
    }

    private fun selectZoomPill(selectedView: TextView) {
        when (selectedView) {
            binding.btnZoomUltrawide -> updateDualZoomUi("0.6", "1×")
            binding.btnLens10, binding.btnZoom1x -> updateDualZoomUi("1.0", "1×")
            binding.btnLens20, binding.btnZoom2x -> updateDualZoomUi("2.0", "2")
            binding.btnZoom5x -> updateDualZoomUi("2.0", "5")
            else -> updateDualZoomUi("1.0", "1×")
        }
    }

    private fun updateDualZoomUi(activeLens: String, activeMultiplier: String) {
        val upperButtons = listOf(binding.btnZoomUltrawide, binding.btnLens10, binding.btnLens20)
        for (btn in upperButtons) {
            if (btn.text == activeLens) {
                btn.setBackgroundResource(R.drawable.bg_zoom_pill_active)
                btn.setTextColor(ContextCompat.getColor(this, R.color.pixel_pill_text_active))
            } else {
                btn.background = null
                btn.setTextColor(ContextCompat.getColor(this, R.color.pixel_text_secondary))
            }
        }

        val lowerButtons = listOf(binding.btnZoom1x, binding.btnZoom2x, binding.btnZoom5x)
        for (btn in lowerButtons) {
            if (btn.text == activeMultiplier) {
                btn.setBackgroundResource(R.drawable.bg_zoom_pill_active)
                btn.setTextColor(ContextCompat.getColor(this, R.color.pixel_pill_text_active))
            } else {
                btn.background = null
                btn.setTextColor(ContextCompat.getColor(this, R.color.pixel_text_secondary))
            }
        }
    }

    private var isUserScrollingCarousel = false
    // Only a finger swipe may change the mode; layout and programmatic scrolls must not.
    private var carouselSwipedByUser = false
    private var lastCarouselScrollX = -1
    private val carouselFlingChecker = object : Runnable {
        override fun run() {
            val currentScrollX = binding.modeCarousel.scrollX
            if (lastCarouselScrollX == currentScrollX) {
                snapCarouselToNearestMode()
            } else {
                lastCarouselScrollX = currentScrollX
                binding.modeCarousel.postDelayed(this, 50)
            }
        }
    }

    private fun setupModeCarousel() {
        binding.modeCarousel.clipToPadding = false
        binding.modeCarousel.overScrollMode = View.OVER_SCROLL_NEVER

        binding.modeCarousel.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                val halfScreen = binding.modeCarousel.width / 2
                if (halfScreen > 0 && binding.modeCamera.width > 0) {
                    binding.modeCarousel.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    padCarousel()
                }
            }
        })

        // Touch & fling snapping for tactile Pixel swipe interaction
        binding.modeCarousel.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    isUserScrollingCarousel = true
                    carouselSwipedByUser = true
                    binding.modeCarousel.removeCallbacks(carouselFlingChecker)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isUserScrollingCarousel = false
                    lastCarouselScrollX = binding.modeCarousel.scrollX
                    binding.modeCarousel.postDelayed(carouselFlingChecker, 50)
                }
            }
            false
        }

        binding.modeCarousel.setOnScrollChangeListener { _, _, _, _, _ ->
            if (!isUserScrollingCarousel) {
                binding.modeCarousel.removeCallbacks(carouselFlingChecker)
                lastCarouselScrollX = binding.modeCarousel.scrollX
                binding.modeCarousel.postDelayed(carouselFlingChecker, 50)
            }
        }
    }

    private fun getCurrentActiveModeView(): TextView {
        return when (currentMode) {
            CameraMode.LONG_EXPOSURE -> binding.modeLongExposure
            CameraMode.PORTRAIT -> binding.modePortrait
            CameraMode.CAMERA -> binding.modeCamera
            CameraMode.NIGHT_SIGHT -> binding.modeNightSight
            CameraMode.PANORAMA -> binding.modePanorama
            // Manual controls are Photo's sliders panel, as in Google Camera.
            CameraMode.PRO -> binding.modeCamera
            CameraMode.VIDEO -> if (slowMode != null) binding.modeSlowMotion else binding.modeVideo
            else -> binding.modeCamera
        }
    }

    /** The mode names shown: photo modes, or (as Google Camera) the video modes once Video is chosen. */
    private fun carouselItems(): List<TextView> =
        if (currentMode == CameraMode.VIDEO) listOf(binding.modeVideo, binding.modeSlowMotion)
        else listOf(binding.modePortrait, binding.modeCamera, binding.modeNightSight, binding.modePanorama, binding.modeLongExposure)

    private val allCarouselItems: List<TextView>
        get() = listOf(
            binding.modePortrait, binding.modeCamera, binding.modeNightSight, binding.modePanorama,
            binding.modeLongExposure, binding.modePro, binding.modeVideo, binding.modeSlowMotion
        )

    /** Shows the photo or the video mode names, padded so the first and last can sit in the middle. */
    private fun padCarousel() {
        val shown = carouselItems()
        for (v in allCarouselItems) v.visibility = if (v in shown) View.VISIBLE else View.GONE
        binding.modeCarouselContent.post {
            val half = binding.modeCarousel.width / 2
            val padStart = (half - shown.first().width / 2).coerceAtLeast(0)
            val padEnd = (half - shown.last().width / 2).coerceAtLeast(0)
            binding.modeCarouselContent.setPadding(padStart, 0, padEnd, 0)
            binding.modeCarouselContent.post { selectModeUi(getCurrentActiveModeView(), smoothScroll = false) }
        }
    }

    /** Acts on a mode name chosen by tap or swipe. */
    private fun chooseCarouselItem(view: TextView) {
        when (view) {
            binding.modeVideo -> {
                if (slowMode != null) setSlowMotion(null) else selectModeUi(view)
            }
            binding.modeSlowMotion -> {
                if (slowMode == null) setSlowMotion(lastSlowMotionSpeed) else selectModeUi(view)
            }
            binding.modePortrait -> setMode(CameraMode.PORTRAIT, view)
            binding.modeNightSight -> setMode(CameraMode.NIGHT_SIGHT, view)
            binding.modePanorama -> setMode(CameraMode.PANORAMA, view)
            binding.modeLongExposure -> setMode(CameraMode.LONG_EXPOSURE, view)
            else -> setMode(CameraMode.CAMERA, binding.modeCamera)
        }
    }

    private fun centerModeView(view: TextView, smooth: Boolean = true) {
        view.post {
            val halfScreen = binding.modeCarousel.width / 2
            if (halfScreen <= 0 || view.width <= 0) return@post
            val viewCenter = view.left + view.width / 2
            val targetScrollX = viewCenter - halfScreen
            if (smooth) {
                binding.modeCarousel.smoothScrollTo(targetScrollX, 0)
            } else {
                binding.modeCarousel.scrollTo(targetScrollX, 0)
            }
        }
    }

    private fun snapCarouselToNearestMode() {
        if (!carouselSwipedByUser) return
        carouselSwipedByUser = false
        val halfScreen = binding.modeCarousel.width / 2
        if (halfScreen <= 0) return
        if (isModeChangeBlocked()) {
            centerModeView(getCurrentActiveModeView(), smooth = true)
            return
        }
        val currentCenter = binding.modeCarousel.scrollX + halfScreen

        var closestView = getCurrentActiveModeView()
        var minDistance = Int.MAX_VALUE
        for (view in carouselItems()) {
            if (view.width <= 0) continue
            val distance = kotlin.math.abs(view.left + view.width / 2 - currentCenter)
            if (distance < minDistance) {
                minDistance = distance
                closestView = view
            }
        }

        centerModeView(closestView, smooth = true)
        if (closestView != getCurrentActiveModeView()) {
            chooseCarouselItem(closestView)
            binding.modeCarousel.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    private fun selectModeUi(view: TextView, smoothScroll: Boolean = true) {
        // Pro shows as Photo: its controls are Photo's sliders panel.
        val selectedView = if (view == binding.modePro) binding.modeCamera else view
        for (v in allCarouselItems) {
            if (v == selectedView) {
                v.setBackgroundResource(R.drawable.bg_mode_pill_active)
                v.setTextColor(ContextCompat.getColor(this, R.color.gc_mode_pill_text))
                v.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            } else {
                v.background = null
                v.setTextColor(ContextCompat.getColor(this, R.color.pixel_text_white))
                v.typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
            }
        }
        centerModeView(selectedView, smooth = smoothScroll)
    }

    private fun isModeChangeBlocked(): Boolean =
        isCapturing || timerJob?.isActive == true || cameraManager.isCapturingPanorama() ||
            slowModeSwitching || slowMotion.isRecording

    private fun setMode(mode: CameraMode, selectedView: TextView) {
        if (currentMode == mode) {
            selectModeUi(selectedView, smoothScroll = true)
            return
        }
        // Rebinding the camera mid-capture drops the frames still being taken.
        if (isModeChangeBlocked()) return
        selectModeUi(selectedView, smoothScroll = true)
        // Get the portrait models ready while the user is still framing.
        if (mode == CameraMode.PORTRAIT) PortraitSegmenter.warmUp(this)

        if (currentMode == CameraMode.PRO) {
            // Manual exposure/focus/white balance belong to Pro only.
            proIsoIndex = 0
            proShutterIndex = 0
            proFocusProgress = 0
            proWbIndex = 0
            cameraManager.resetManualState()
            selectProTab(activeProTab)
        }

        // If recording video when switching modes, stop it first
        if (cameraManager.isRecordingVideo()) {
            stopVideoRecordingUi()
            cameraManager.stopVideoRecording()
        }

        // Slow motion belongs to Video only; hand the camera back before the new mode binds.
        if (slowMode != null) {
            // The new mode may only bind once the slow-motion session has let go of the camera.
            leaveSlowMotion(rebind = false) { setMode(mode, selectedView) }
            return
        }

        currentMode = mode
        cameraManager.currentCameraMode = mode
        if (mode != CameraMode.PORTRAIT) hidePortraitPreview()

        // Update Tier 3 Toggle Pill
        updatePhotoVideoToggle(isVideo = (mode == CameraMode.VIDEO))

        if (cameraManager.isFrontCamera()) updateSensorLabel()
        // Update Pixel Shutter color per mode
        when (mode) {
            CameraMode.NIGHT_SIGHT, CameraMode.LONG_EXPOSURE -> binding.btnShutter.setImageResource(R.drawable.bg_shutter_night)
            CameraMode.VIDEO -> binding.btnShutter.setImageResource(R.drawable.bg_shutter_video)
            CameraMode.PORTRAIT -> binding.btnShutter.setImageResource(R.drawable.bg_shutter_portrait)
            else -> binding.btnShutter.setImageResource(R.drawable.bg_shutter_button)
        }

        // Update Overlays Visibility per Mode
        when (mode) {
            CameraMode.NIGHT_SIGHT -> {
                stillStartTime = 0L
                cameraManager.isAstrophotographyActive = false
                binding.tvHdrBadge.text = "NIGHT SIGHT"
                binding.tvHdrBadge.setTextColor(ContextCompat.getColor(this, R.color.pixel_pill_text_active))
                showProPanel(false)
                binding.histogramView.visibility = View.GONE
                binding.overlayPortraitAperture.visibility = View.GONE
                binding.overlayPanoramaGuide.visibility = View.GONE
            }
            CameraMode.LONG_EXPOSURE -> {
                binding.tvHdrBadge.text = "LONG EXPOSURE"
                binding.tvHdrBadge.setTextColor(ContextCompat.getColor(this, R.color.pixel_pill_text_active))
                showProPanel(false)
                binding.histogramView.visibility = View.GONE
                binding.overlayPortraitAperture.visibility = View.GONE
                binding.overlayPanoramaGuide.visibility = View.GONE
            }
            CameraMode.PORTRAIT -> {
                binding.tvHdrBadge.text = "PORTRAIT DEPTH"
                binding.tvHdrBadge.setTextColor(ContextCompat.getColor(this, R.color.pixel_pill_text_active))
                showProPanel(false)
                binding.histogramView.visibility = View.GONE
                binding.overlayPortraitAperture.visibility = View.GONE
                binding.overlayPanoramaGuide.visibility = View.GONE
            }
            CameraMode.PRO -> {
                binding.tvHdrBadge.text = "PRO MANUAL"
                binding.tvHdrBadge.setTextColor(ContextCompat.getColor(this, R.color.pixel_pill_text_active))
                showProPanel(true)
                binding.histogramView.visibility = View.GONE
                binding.overlayPortraitAperture.visibility = View.GONE
                binding.overlayPanoramaGuide.visibility = View.GONE
            }
            CameraMode.VIDEO -> {
                updateVideoBadgeText()
                showProPanel(false)
                binding.histogramView.visibility = View.GONE
                binding.overlayPortraitAperture.visibility = View.GONE
                binding.overlayPanoramaGuide.visibility = View.GONE
            }
            CameraMode.CAMERA -> {
                binding.tvHdrBadge.text = "HDR+ ON"
                binding.tvHdrBadge.setTextColor(ContextCompat.getColor(this, R.color.pixel_pill_text_active))
                showProPanel(false)
                binding.histogramView.visibility = View.GONE
                binding.overlayPortraitAperture.visibility = View.GONE
                binding.overlayPanoramaGuide.visibility = View.GONE
            }
            CameraMode.PANORAMA -> {
                binding.tvHdrBadge.text = "PANORAMA"
                binding.tvHdrBadge.setTextColor(ContextCompat.getColor(this, R.color.pixel_pill_text_active))
                showProPanel(false)
                binding.histogramView.visibility = View.GONE
                binding.overlayPortraitAperture.visibility = View.GONE
                binding.overlayPanoramaGuide.visibility = View.VISIBLE
                resetPanoramaUi()
            }
        }

        // Google Camera's Panorama shows neither the switch button nor the level.
        binding.btnSwitchCamera.visibility = if (mode == CameraMode.PANORAMA) View.INVISIBLE else View.VISIBLE
        binding.horizonLevelContainer.visibility = if (mode == CameraMode.PANORAMA) View.GONE else View.VISIBLE

        // Rebind use cases for optimal performance in active mode
        applyPreviewScale()
        cameraManager.bindCameraUseCases()
        updateZoomPillVisibility()
        updateQuickSettingsUi()
        updateProControlsButton()
        lastTapFocus = null
        setFocusLocked(false)
        hideQrResult()
        prefs.edit().putString(SettingsActivity.KEY_LAST_MODE, mode.name).apply()
    }

    /** The manual-controls button belongs to Pro mode only; it is highlighted while the panel is open. */
    /**
     * Bottom right, as Google Camera: the sliders button in Photo (manual controls) and Portrait
     * (blur), a moon chip in Night Sight, the speed in Slow Motion, nothing elsewhere.
     */
    private fun updateProControlsButton() {
        val chip = binding.chipBottomRight
        when {
            currentMode == CameraMode.NIGHT_SIGHT -> {
                chip.text = if (cameraManager.isAstrophotographyActive) "Astro" else "Auto"
                chip.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_moon, 0, 0, 0)
                chip.visibility = View.VISIBLE
            }
            currentMode == CameraMode.VIDEO && slowMode != null -> {
                chip.text = if (slowMode == com.motorola.pixelcamera.core.SlowMotionCamera.Mode.X8) "1/8×" else "1/4×"
                chip.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0)
                chip.visibility = View.VISIBLE
            }
            else -> chip.visibility = View.GONE
        }
        val sliders = currentMode == CameraMode.CAMERA || currentMode == CameraMode.PRO || currentMode == CameraMode.PORTRAIT
        // INVISIBLE keeps its slot, so the photo/video toggle stays centred
        binding.btnProControls.visibility = if (sliders && chip.visibility != View.VISIBLE) View.VISIBLE else View.INVISIBLE
        val panelOpen = binding.proControlPanel.visibility == View.VISIBLE ||
            binding.overlayPortraitAperture.visibility == View.VISIBLE
        binding.btnProControls.setBackgroundResource(if (panelOpen) R.drawable.bg_qs_btn_active else R.drawable.bg_settings_circle)
        ImageViewCompat.setImageTintList(
            binding.btnProControls,
            ContextCompat.getColorStateList(this, if (panelOpen) R.color.pixel_icon_active else R.color.pixel_text_white)
        )
    }

    /** Sliders: Photo opens manual controls (Pro underneath), Portrait the blur strength. */
    private fun onSlidersButton() {
        when (currentMode) {
            CameraMode.PORTRAIT -> {
                val show = binding.overlayPortraitAperture.visibility != View.VISIBLE
                binding.overlayPortraitAperture.visibility = if (show) View.VISIBLE else View.GONE
                updateProControlsButton()
            }
            CameraMode.CAMERA -> setMode(CameraMode.PRO, binding.modePro)
            CameraMode.PRO -> setMode(CameraMode.CAMERA, binding.modeCamera)
            else -> {}
        }
    }

    /** Night Sight: what the next shot will do. Slow Motion: switch between 1/4 and 1/8 speed. */
    private fun onBottomRightChip() {
        when {
            currentMode == CameraMode.NIGHT_SIGHT -> showHint(
                if (cameraManager.isAstrophotographyActive) "Astrophotography: phone is steady" else "Night Sight · rest the phone for astrophotography"
            )
            currentMode == CameraMode.VIDEO && slowMode != null && !slowMotion.isRecording && !slowModeSwitching -> {
                val next = if (slowMode == com.motorola.pixelcamera.core.SlowMotionCamera.Mode.X8)
                    com.motorola.pixelcamera.core.SlowMotionCamera.Mode.X4 else com.motorola.pixelcamera.core.SlowMotionCamera.Mode.X8
                lastSlowMotionSpeed = next
                leaveSlowMotion(rebind = false) { setSlowMotion(next) }
            }
        }
    }

    private fun showFocusReticleAndSlider(x: Float, y: Float) {
        val container = binding.focusReticleContainer
        val density = resources.displayMetrics.density

        reticleAutoHideJob?.cancel()
        container.animate().cancel()
        container.alpha = 1f
        container.visibility = View.VISIBLE

        // Center focusRing (64dp) on tap point (x, y)
        // focusRing center is at x = 32dp inside container, y = 60dp (centered in 120dp track)
        val ringCenterX = 32f * density
        val ringCenterY = 60f * density

        var targetX = x - ringCenterX
        var targetY = y - ringCenterY

        val parentW = binding.viewFinder.width.toFloat()
        val parentH = binding.viewFinder.height.toFloat()
        val containerW = 120f * density
        val containerH = 130f * density

        // Clamp to viewfinder bounds
        if (targetX + containerW > parentW - 8 * density) {
            targetX = parentW - containerW - 8 * density
        }
        if (targetX < 8 * density) {
            targetX = 8 * density
        }

        if (targetY + containerH > parentH - 8 * density) {
            targetY = parentH - containerH - 8 * density
        }
        if (targetY < 8 * density) {
            targetY = 8 * density
        }

        container.x = targetX
        container.y = targetY

        // Animate focus ring pulse
        binding.focusRing.scaleX = 1.35f
        binding.focusRing.scaleY = 1.35f
        binding.focusRing.animate()
            .scaleX(1.0f)
            .scaleY(1.0f)
            .setDuration(220)
            .start()

        // Sync sun position
        val range = cameraManager.getExposureCompensationRange()
        updateExposureThumbPosition(currentEvIndex, range)

        // Schedule auto-hide after 3.5 seconds
        scheduleReticleAutoHide(3500L)
    }

    private fun updateExposureThumbPosition(evIndex: Int, range: android.util.Range<Int>) {
        val density = resources.displayMetrics.density
        val maxTravelPx = 38f * density

        val fraction = if (range.upper != range.lower) {
            (evIndex.toFloat() - range.lower) / (range.upper - range.lower).toFloat()
        } else {
            0.5f
        }

        val translationY = (0.5f - fraction) * 2f * maxTravelPx
        binding.ivExposureSun.translationY = translationY

        val step = cameraManager.getExposureCompensationStep()
        val evValue = evIndex * step
        binding.tvExposureEvBadge.text = String.format("%+.1f EV", evValue)
    }

    private fun showExposureEvBadge() {
        binding.tvExposureEvBadge.animate().cancel()
        binding.tvExposureEvBadge.alpha = 1f
        binding.tvExposureEvBadge.visibility = View.VISIBLE
    }

    private fun scheduleReticleAutoHide(delayMs: Long) {
        reticleAutoHideJob?.cancel()
        reticleAutoHideJob = lifecycleScope.launch {
            delay(delayMs)
            if (isActive && !isDraggingExposure) {
                binding.focusReticleContainer.animate()
                    .alpha(0f)
                    .setDuration(350)
                    .withEndAction {
                        binding.focusReticleContainer.visibility = View.GONE
                        binding.tvExposureEvBadge.visibility = View.GONE
                    }
                    .start()
            }
        }
    }

    private fun onShutterClicked() {
        binding.btnShutter.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)

        if (currentMode == CameraMode.VIDEO) {
            handleVideoShutter()
            return
        }

        if (currentMode == CameraMode.PANORAMA) {
            handlePanoramaShutter()
            return
        }

        if (isCapturing) return

        if (timerJob?.isActive == true) {
            // Second press cancels a running countdown.
            timerJob?.cancel()
            binding.tvCountdown.visibility = View.GONE
            return
        }

        if (timerDuration > 0) {
            timerJob = lifecycleScope.launch {
                binding.tvCountdown.visibility = View.VISIBLE
                for (i in timerDuration downTo 1) {
                    binding.tvCountdown.text = i.toString()
                    delay(1000)
                }
                binding.tvCountdown.visibility = View.GONE
                timerJob = null
                executePhotoCapture()
            }
        } else {
            executePhotoCapture()
        }
    }

    private fun executePhotoCapture() {
        if (isCapturing) return
        isCapturing = true

        binding.btnShutter.animate().scaleX(0.88f).scaleY(0.88f).setDuration(100).withEndAction {
            binding.btnShutter.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
        }.start()

        binding.btnShutter.alpha = 0.6f

        val isFrontFlash = cameraManager.isFrontCamera() && cameraManager.flashMode != ImageCapture.FLASH_MODE_OFF
        if (isFrontFlash) {
            binding.overlaySelfieBurst.visibility = View.VISIBLE
            setScreenBrightness(1.0f)
        }

        lifecycleScope.launch {
            if (isFrontFlash) {
                delay(120)
            }
            val blurStrength = portraitBlurStrength()
            val focus = lastTapFocus ?: PointF(0.5f, 0.45f)
            val uri: Uri? = guarded("Photo") {
                cameraManager.capturePhoto(currentMode, blurStrength, focus.x, focus.y) { progress ->
                    runOnUiThread { showCaptureProgress(progress) }
                }
            }

            if (isFrontFlash) {
                binding.overlaySelfieBurst.visibility = View.GONE
                checkSelfieRingLight()
            }

            endCaptureFeedback()
            isCapturing = false

            if (uri != null) {
                latestCapturedUri = uri
                loadLatestGalleryThumbnail()
                binding.cardGalleryThumbnail.animate()
                    .scaleX(1.15f).scaleY(1.15f)
                    .setDuration(120)
                    .withEndAction {
                        binding.cardGalleryThumbnail.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
                    }.start()
            } else {
                showHint("Capture failed")
            }
        }
    }

    /**
     * Capture feedback lives on the shutter and the gallery thumbnail, so nothing covers the picture:
     * a ring and a frames-left count while the phone has to stay still, then a spinner on the thumbnail.
     */
    private fun showCaptureProgress(progress: CaptureProgress) {
        when (progress) {
            is CaptureProgress.Frames -> {
                val isBurst = progress.total > 1
                if (progress.done == 0) {
                    if (isBurst) {
                        binding.progressShutterRing.setProgressCompat(0, false)
                        binding.progressShutterRing.visibility = View.VISIBLE
                        binding.tvShutterCount.visibility = View.VISIBLE
                        showCaptureChip("Hold steady", 0, duringCapture = true)
                    } else {
                        blinkViewfinder()
                        binding.progressThumbnailSpin.visibility = View.VISIBLE
                    }
                }
                if (isBurst) {
                    binding.progressShutterRing.setProgressCompat(1000 * progress.done / progress.total, true)
                    binding.tvShutterCount.text = (progress.total - progress.done).coerceAtLeast(1).toString()
                }
            }
            CaptureProgress.Processing -> {
                blinkViewfinder()
                hideShutterProgress()
                binding.progressThumbnailSpin.visibility = View.VISIBLE
            }
        }
    }

    private fun hideShutterProgress() {
        binding.progressShutterRing.visibility = View.INVISIBLE
        binding.tvShutterCount.visibility = View.GONE
        captureChipHeldByCapture = false
        binding.tvCaptureChip.visibility = View.GONE
    }

    private fun endCaptureFeedback() {
        hideShutterProgress()
        binding.progressThumbnailSpin.visibility = View.GONE
        binding.btnShutter.alpha = 1f
    }

    private fun blinkViewfinder() {
        binding.viewShutterBlink.animate().cancel()
        binding.viewShutterBlink.alpha = 0.9f
        binding.viewShutterBlink.animate().alpha(0f).setDuration(220).start()
    }

    /** [duringCapture] chips belong to a running burst; the others describe what the next shot will do. */
    private fun showCaptureChip(text: String, iconRes: Int, duringCapture: Boolean) {
        captureChipHeldByCapture = duringCapture
        binding.tvCaptureChip.text = text
        binding.tvCaptureChip.setCompoundDrawablesRelativeWithIntrinsicBounds(iconRes, 0, 0, 0)
        binding.tvCaptureChip.visibility = View.VISIBLE
    }

    /** One short line for something the user has to act on. Success never gets a message. */
    private fun showHint(message: String) {
        hintJob?.cancel()
        binding.tvHint.text = message
        binding.tvHint.alpha = 1f
        binding.tvHint.visibility = View.VISIBLE
        hintJob = lifecycleScope.launch {
            delay(HINT_DURATION_MS)
            binding.tvHint.animate().alpha(0f).setDuration(200).withEndAction {
                binding.tvHint.visibility = View.GONE
            }.start()
        }
    }

    /** Active settings show as chips in the top bar for as long as they apply. */
    private fun updateStatusChips() {
        val photoMode = currentMode != CameraMode.VIDEO
        binding.chipTimer.text = "${timerDuration}s"
        binding.chipTimer.visibility = if (photoMode && timerDuration > 0) View.VISIBLE else View.GONE
        binding.chipHdr.text = if (hdrMode == 2) "HDR+ E" else "HDR+"
        binding.chipHdr.visibility = if (currentMode == CameraMode.CAMERA && hdrMode > 0) View.VISIBLE else View.GONE
        binding.chipRaw.visibility = if (cameraManager.isRawActive()) View.VISIBLE else View.GONE

        val mirror = cameraManager.mirrorEnabled
        binding.chipMirror.visibility = if (mirror && currentMode != CameraMode.PANORAMA) View.VISIBLE else View.GONE
        val mirrorText = when {
            !mirror -> "Off"
            cameraManager.isFrontCamera() -> "On · saved as previewed"
            else -> "On · flipped left-to-right"
        }
        binding.tvQsMirrorSubtitle.text = mirrorText
        binding.tvQsVideoMirrorSubtitle.text = mirrorText
        setQsTextButtonActive(binding.btnQsMirrorOff, !mirror)
        setQsTextButtonActive(binding.btnQsMirrorOn, mirror)
        setQsTextButtonActive(binding.btnQsVideoMirrorOff, !mirror)
        setQsTextButtonActive(binding.btnQsVideoMirrorOn, mirror)

        val motion = cameraManager.topShotMode != CameraManager.TopShotMode.OFF
        val motionApplies = currentMode == CameraMode.CAMERA || currentMode == CameraMode.PORTRAIT
        binding.chipMotion.visibility = if (motion && motionApplies) View.VISIBLE else View.GONE
        binding.tvQsMotionSubtitle.text = if (motion) "On · Photo and Portrait" else "Off"
        setQsTextButtonActive(binding.btnQsMotionOff, !motion)
        setQsTextButtonActive(binding.btnQsMotionOn, motion)
    }

    private fun setQsTextButtonActive(button: TextView, active: Boolean) {
        button.setBackgroundResource(if (active) R.drawable.bg_qs_btn_active else R.drawable.bg_qs_btn_inactive)
        button.setTextColor(ContextCompat.getColor(this, if (active) R.color.gc_toggle_icon_active else R.color.gc_icon))
    }

    /** Mirror is remembered separately for the front and the back camera. */
    private fun setMirror(enabled: Boolean) {
        if (cameraManager.isRecordingVideo() || isCapturing) return
        if (cameraManager.isFrontCamera()) {
            cameraManager.mirrorFront = enabled
            prefs.edit().putBoolean(SettingsActivity.KEY_MIRROR_FRONT, enabled).apply()
        } else {
            cameraManager.mirrorBack = enabled
            prefs.edit().putBoolean(SettingsActivity.KEY_MIRROR_BACK, enabled).apply()
        }
        cameraManager.bindCameraUseCases()
        updateQuickSettingsUi()
    }

    private fun setMotionPhoto(enabled: Boolean) {
        cameraManager.topShotMode = if (enabled) CameraManager.TopShotMode.ON else CameraManager.TopShotMode.OFF
        prefs.edit().putBoolean(SettingsActivity.KEY_MOTION_PHOTO, enabled).apply()
        updateQuickSettingsUi()
    }

    /** Long-press holds focus and exposure where you pressed; the next tap releases them. */
    private fun setFocusLocked(locked: Boolean) {
        binding.chipLock.visibility = if (locked) View.VISIBLE else View.GONE
    }

    /**
     * Where a viewfinder touch lands in the saved photo, as 0..1 fractions.
     * The viewfinder crops (fill) or letterboxes (fit) the picture, and the front preview is a mirror.
     */
    private fun viewToImagePoint(x: Float, y: Float): PointF {
        val vw = binding.viewFinder.width.toFloat().coerceAtLeast(1f)
        val vh = binding.viewFinder.height.toFloat().coerceAtLeast(1f)
        val wide = cameraManager.aspectRatio == AspectRatio.RATIO_16_9 || currentMode == CameraMode.VIDEO
        val imageAspect = if (wide) 9f / 16f else 3f / 4f   // portrait width / height
        val viewAspect = vw / vh
        // Fraction of the image seen across each view axis (>1 means the view shows bars)
        val fill = binding.viewFinder.scaleType == androidx.camera.view.PreviewView.ScaleType.FILL_CENTER
        val spanX = if ((viewAspect < imageAspect) == fill) viewAspect / imageAspect else 1f
        val spanY = if ((viewAspect < imageAspect) == fill) 1f else imageAspect / viewAspect
        var ix = 0.5f + (x / vw - 0.5f) * spanX
        val iy = 0.5f + (y / vh - 0.5f) * spanY
        // Front preview is mirrored; the saved photo is not unless mirror is on
        if (cameraManager.isFrontCamera() && !cameraManager.mirrorFront) ix = 1f - ix
        return PointF(ix.coerceIn(0f, 1f), iy.coerceIn(0f, 1f))
    }

    /** Burst: one photo after another for as long as the shutter stays pressed. */
    private fun startHoldBurst(): Boolean {
        if (currentMode != CameraMode.CAMERA || isCapturing || timerJob?.isActive == true) return false
        isCapturing = true
        binding.tvShutterCount.text = "0"
        binding.tvShutterCount.visibility = View.VISIBLE
        lifecycleScope.launch {
            val lastUri = guarded("Burst") {
                cameraManager.captureBurst({ shutterHeld }, MAX_HOLD_BURST) { taken ->
                    binding.tvShutterCount.text = taken.toString()
                    blinkViewfinder()
                }
            }
            binding.tvShutterCount.visibility = View.GONE
            isCapturing = false
            if (lastUri != null) {
                latestCapturedUri = lastUri
                loadLatestGalleryThumbnail()
                updateStorageBadge()
            } else {
                showHint("Capture failed")
            }
        }
        return true
    }

    private fun toggleVideoPause() {
        if (!cameraManager.isRecordingVideo()) return
        videoPaused = !videoPaused
        if (videoPaused) cameraManager.pauseVideoRecording() else cameraManager.resumeVideoRecording()
        binding.btnVideoPause.setImageResource(if (videoPaused) R.drawable.ic_play_arrow else R.drawable.ic_pause)
        binding.btnVideoPause.contentDescription = if (videoPaused) "Resume recording" else "Pause recording"
        if (videoPaused) {
            recordingDotAnimator?.pause()
            binding.viewRecordingDot.alpha = 0.3f
        } else {
            recordingDotAnimator?.resume()
        }
    }

    /** A photo while recording: the frame on screen, at video resolution. */
    private fun takeVideoSnapshot() {
        val frame = binding.viewFinder.bitmap ?: return
        blinkViewfinder()
        lifecycleScope.launch {
            val uri = cameraManager.saveVideoSnapshot(frame)
            if (uri == null) showHint("Could not save the photo")
        }
    }

    private fun showQrResult(text: String) {
        if (currentMode != CameraMode.CAMERA || isCapturing) return
        qrText = text
        val isLink = text.startsWith("http://") || text.startsWith("https://")
        binding.tvQrResult.text = if (isLink) "Open  ${text.substringAfter("://")}" else "Copy  $text"
        binding.tvQrResult.visibility = View.VISIBLE
        qrHideJob?.cancel()
        qrHideJob = lifecycleScope.launch {
            delay(QR_RESULT_MS)
            hideQrResult()
        }
    }

    private fun hideQrResult() {
        qrHideJob?.cancel()
        binding.tvQrResult.visibility = View.GONE
    }

    private fun openQrResult() {
        val text = qrText ?: return
        hideQrResult()
        if (text.startsWith("http://") || text.startsWith("https://")) {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(text))) }
                .onFailure { showHint("No app can open this link") }
        } else {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("QR code", text))
            showHint("Copied")
        }
    }

    /** Opens in the mode used last, when that setting is on. */
    private fun restoreLastMode() {
        if (!prefs.getBoolean(SettingsActivity.KEY_REMEMBER_MODE, false)) return
        when (prefs.getString(SettingsActivity.KEY_LAST_MODE, null)) {
            CameraMode.PORTRAIT.name -> binding.modePortrait.performClick()
            CameraMode.NIGHT_SIGHT.name -> binding.modeNightSight.performClick()
            CameraMode.LONG_EXPOSURE.name -> binding.modeLongExposure.performClick()
            CameraMode.PANORAMA.name -> binding.modePanorama.performClick()
            CameraMode.PRO.name -> binding.modePro.performClick()
            CameraMode.VIDEO.name -> binding.btnToggleVideo.performClick()
        }
    }

    /** In dim light Photo mode merges several frames; the moon chip says so before the shot. */
    private fun startLowLightWatcher() {
        lowLightJob?.cancel()
        lowLightJob = lifecycleScope.launch {
            while (isActive) {
                // Google Camera says nothing about the next shot over the viewfinder; only a
                // capture in progress shows a chip.
                if (!captureChipHeldByCapture) binding.tvCaptureChip.visibility = View.GONE
                delay(LOW_LIGHT_POLL_MS)
            }
        }
    }

    /** Switches slow motion on in [mode], or off with null. Video mode, rear camera only. */
    /** Speed Slow Motion opens with, from the last one chosen. */
    private var lastSlowMotionSpeed = com.motorola.pixelcamera.core.SlowMotionCamera.Mode.X4

    private fun setSlowMotion(mode: com.motorola.pixelcamera.core.SlowMotionCamera.Mode?) {
        if (mode != null) lastSlowMotionSpeed = mode
        if (mode == slowMode || slowModeSwitching || slowMotion.isRecording || cameraManager.isRecordingVideo()) return
        if (mode == null) {
            leaveSlowMotion(rebind = true)
            return
        }
        if (cameraManager.isFrontCamera()) {
            showHint("Slow motion uses the rear camera")
            return
        }
        slowModeSwitching = true
        slowMode = mode
        updateVideoQsUi()
        updateZoomPillVisibility()
        cameraManager.releaseCamera()
        val view = binding.slowMotionPreview
        fitSlowMotionPreview()
        view.visibility = View.VISIBLE
        val start = { texture: android.graphics.SurfaceTexture ->
            slowMotion.open(mode, texture) { ok ->
                slowModeSwitching = false
                if (ok) {
                    showHint("Slow motion ${mode.label} · needs bright light · no sound")
                } else {
                    showHint("Slow motion is not available")
                    leaveSlowMotion(rebind = true)
                }
            }
        }
        val ready = view.surfaceTexture
        if (view.isAvailable && ready != null) {
            start(ready)
        } else {
            view.surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(texture: android.graphics.SurfaceTexture, w: Int, h: Int) {
                    view.surfaceTextureListener = null
                    start(texture)
                }
                override fun onSurfaceTextureSizeChanged(texture: android.graphics.SurfaceTexture, w: Int, h: Int) {}
                override fun onSurfaceTextureDestroyed(texture: android.graphics.SurfaceTexture) = true
                override fun onSurfaceTextureUpdated(texture: android.graphics.SurfaceTexture) {}
            }
        }
    }

    /** Ends slow motion, saving any video in progress, and gives the camera back to the normal preview. */
    private fun leaveSlowMotion(rebind: Boolean, then: () -> Unit = {}) {
        if (slowMode == null) return
        if (slowMotion.isRecording) stopVideoRecordingUi()
        slowMode = null
        slowModeSwitching = true
        updateVideoQsUi()
        updateZoomPillVisibility()
        binding.slowMotionPreview.surfaceTextureListener = null
        slowMotion.close {
            slowModeSwitching = false
            binding.slowMotionPreview.visibility = View.GONE
            loadLatestGalleryThumbnail()
            if (rebind) cameraManager.bindCameraUseCases()
            then()
        }
    }

    /** The slow-motion picture is 16:9, shown upright and filling the viewfinder area. */
    private fun fitSlowMotionPreview() {
        val parent = binding.slowMotionPreview.parent as View
        val pw = parent.width
        val ph = parent.height
        if (pw == 0 || ph == 0) return
        // Fills the width like the normal video preview; the card clips what overhangs.
        binding.slowMotionPreview.layoutParams = android.widget.FrameLayout.LayoutParams(pw, pw * 16 / 9, android.view.Gravity.CENTER)
    }

    private fun setLowLightVideo(enabled: Boolean) {
        if (cameraManager.isRecordingVideo()) return
        prefs.edit().putBoolean(SettingsActivity.KEY_LOW_LIGHT_VIDEO, enabled).apply()
        cameraManager.setLowLightVideoEnabled(enabled)
        updateVideoQsUi()
        if (enabled) showHint("Low light on · frame rate drops in the dark")
    }

    private fun handleSlowMotionShutter() {
        if (slowModeSwitching) return
        if (slowMotion.isRecording) {
            stopVideoRecordingUi()
            slowMotion.stopRecording { uri ->
                if (uri != null) loadLatestGalleryThumbnail() else showHint("Slow-motion video was not saved")
            }
        } else {
            slowMotion.startRecording { ok ->
                if (ok) {
                    startVideoRecordingUi()
                    // Pausing and stills are not part of the slow-motion session.
                    binding.btnVideoPause.visibility = View.GONE
                    binding.btnVideoSnapshot.visibility = View.GONE
                } else {
                    showHint("Failed to start recording")
                }
            }
        }
    }

    private fun handleVideoShutter() {
        if (slowMode != null) {
            handleSlowMotionShutter()
            return
        }
        if (cameraManager.isRecordingVideo()) {
            // Stop recording
            stopVideoRecordingUi()
            cameraManager.stopVideoRecording()
        } else {
            // Start recording
            val started = cameraManager.startVideoRecording { event ->
                runOnUiThread {
                    when (event) {
                        is VideoRecordEvent.Start -> {
                            startVideoRecordingUi()
                        }
                        is VideoRecordEvent.Finalize -> {
                            stopVideoRecordingUi()
                            if (!event.hasError()) {
                                loadLatestGalleryThumbnail()
                            } else {
                                showHint("Video record error: ${event.error}")
                            }
                        }
                    }
                }
            }
            if (started) {
                startVideoRecordingUi()
            } else {
                showHint("Failed to start recording")
            }
        }
    }

    private fun handlePanoramaShutter() {
        if (!cameraManager.isCapturingPanorama()) {
            cameraManager.startPanoramaCapture()
            binding.btnShutter.setImageResource(R.drawable.bg_shutter_video_recording)
            resetPanoramaUi()
            binding.panoStripFrame.visibility = View.VISIBLE
            showPanoramaMessage("Move the phone slowly to the left or right")
        } else {
            // The automatic finish at 100% and a tap can arrive together; only one may run.
            if (panoramaFinishing) return
            panoramaFinishing = true
            showPanoramaMessage("Saving panorama…")
            binding.btnShutter.setImageResource(R.drawable.bg_shutter_button)
            lifecycleScope.launch {
                binding.progressThumbnailSpin.visibility = View.VISIBLE
                val uri = guarded("Panorama") { cameraManager.stopPanoramaCapture() }
                // The engine has freed the strip picture; the view must not draw it again.
                binding.ivPanoramaStrip.setImageDrawable(null)
                panoramaFinishing = false
                resetPanoramaUi()
                binding.progressThumbnailSpin.visibility = View.GONE
                if (uri != null) {
                    latestCapturedUri = uri
                    loadLatestGalleryThumbnail()
                    updateStorageBadge()
                } else {
                    showPanoramaMessage("Capture stopped. Keep turning the phone a little further.")
                }
            }
        }
    }

    private fun setupAudioZoom() {
        cameraManager.audioZoomManager.onAudioZoomStateChanged = { isActive, zoomRatio, _ ->
            runOnUiThread {
                if (isActive) {
                    binding.tvAudioZoomIndicator.visibility = View.VISIBLE
                    binding.tvAudioZoomIndicator.text = "AUDIO ZOOM ${String.format(Locale.US, "%.1f", zoomRatio)}×"
                } else {
                    binding.tvAudioZoomIndicator.visibility = View.GONE
                }
            }
        }
    }

    private var panoramaFinishing = false

    /**
     * Runs a capture so that a failure inside it (running out of memory in a heavy merge is the
     * likely one) ends in a message, not in the app closing.
     */
    private suspend fun <T> guarded(what: String, block: suspend () -> T?): T? =
        try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            android.util.Log.e("MainActivity", "$what failed", t)
            if (t is OutOfMemoryError) System.gc()
            showHint(if (t is OutOfMemoryError) "Not enough memory · try again" else "$what failed")
            null
        }

    /** Slider position to the blur scale the Portrait photo and its live preview use. */
    private fun portraitBlurStrength(): Float = 2f + (portraitBlurProgress / 100f) * 24f

    /** The live Portrait blur, laid over the viewfinder with the same framing and mirroring. */
    private fun setupPortraitPreview() {
        cameraManager.portraitPreviewBlur = portraitBlurStrength()
        cameraManager.onPortraitPreview = { layer ->
            runOnUiThread { showPortraitPreview(layer) }
        }
    }

    private fun showPortraitPreview(layer: android.graphics.Bitmap?) {
        val view = binding.ivPortraitPreview
        if (layer == null || currentMode != CameraMode.PORTRAIT || isFinishing) {
            hidePortraitPreview()
            return
        }
        view.scaleType = if (binding.viewFinder.scaleType == androidx.camera.view.PreviewView.ScaleType.FIT_CENTER) {
            ImageView.ScaleType.FIT_CENTER
        } else {
            ImageView.ScaleType.CENTER_CROP
        }
        // The viewfinder shows the front camera (and the back one with its mirror on) mirrored.
        view.scaleX = if (cameraManager.isFrontCamera() || cameraManager.mirrorBack) -1f else 1f
        view.setImageBitmap(layer)
        view.visibility = View.VISIBLE
    }

    private fun hidePortraitPreview() {
        binding.ivPortraitPreview.visibility = View.GONE
        binding.ivPortraitPreview.setImageDrawable(null)
    }

    /** Band with both arrows and nothing else, as Google Camera shows before a sweep. */
    private fun resetPanoramaUi() {
        binding.ivPanoramaArrow.visibility = View.VISIBLE
        binding.ivPanoramaArrowLeft.visibility = View.VISIBLE
        binding.panoStripFrame.visibility = View.GONE
        binding.ivPanoramaStrip.scaleType = ImageView.ScaleType.FIT_START
        binding.pbPanoramaProgress.progress = 0
        binding.tvPanoramaPrompt.removeCallbacks(hidePanoramaMessage)
        binding.tvPanoramaPrompt.visibility = View.GONE
    }

    private val hidePanoramaMessage = Runnable { binding.tvPanoramaPrompt.visibility = View.GONE }

    private fun showPanoramaMessage(text: String) {
        binding.tvPanoramaPrompt.text = text
        binding.tvPanoramaPrompt.visibility = View.VISIBLE
        binding.tvPanoramaPrompt.removeCallbacks(hidePanoramaMessage)
        binding.tvPanoramaPrompt.postDelayed(hidePanoramaMessage, 4000)
    }

    /** Audio-zoom videos reach the gallery a moment after recording stops, once sound and picture are joined. */
    private fun setupZoomedVideoSaving() {
        cameraManager.onZoomedVideoSaved = { uri ->
            if (uri == null) showHint("Video could not be saved") else {
                latestCapturedUri = uri
                loadLatestGalleryThumbnail()
            }
        }
    }

    private fun setupPanoramaEngine() {
        cameraManager.panoramaEngine.onPanoramaProgress = { degrees, progressPercent, stripPreview ->
            runOnUiThread {
                binding.tvPanoramaAngle.text = "${degrees.toInt()}° / 180°"
                binding.pbPanoramaProgress.progress = progressPercent
                // Once the way is clear, only the arrow being followed stays, as Google Camera.
                when (cameraManager.panoramaEngine.direction) {
                    1 -> binding.ivPanoramaArrowLeft.visibility = View.INVISIBLE
                    -1 -> binding.ivPanoramaArrow.visibility = View.INVISIBLE
                }
                if (cameraManager.panoramaEngine.direction == -1) binding.ivPanoramaStrip.scaleType = ImageView.ScaleType.FIT_END
                if (stripPreview != null && !stripPreview.isRecycled && !panoramaFinishing) {
                    binding.ivPanoramaStrip.setImageBitmap(stripPreview)
                }
                if (progressPercent >= 100 && cameraManager.isCapturingPanorama()) {
                    handlePanoramaShutter()
                }
            }
        }
    }

    private fun startVideoRecordingUi() {
        binding.btnShutter.setImageResource(R.drawable.bg_shutter_video_recording)
        binding.videoRecordingBanner.visibility = View.VISIBLE
        videoPaused = false
        binding.btnVideoPause.setImageResource(R.drawable.ic_pause)
        binding.btnVideoPause.visibility = View.VISIBLE
        binding.btnVideoSnapshot.visibility = View.VISIBLE
        binding.cardGalleryThumbnail.visibility = View.INVISIBLE
        binding.btnSwitchCamera.visibility = View.INVISIBLE

        recordingSeconds = 0
        binding.tvVideoTimer.text = "00:00"

        // Blinking recording dot
        recordingDotAnimator = ObjectAnimator.ofFloat(binding.viewRecordingDot, "alpha", 1f, 0.2f).apply {
            duration = 500
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }

        // Live timer job
        videoTimerJob?.cancel()
        videoTimerJob = lifecycleScope.launch {
            while (isActive) {
                delay(1000)
                if (videoPaused) continue
                recordingSeconds++
                val min = recordingSeconds / 60
                val sec = recordingSeconds % 60
                binding.tvVideoTimer.text = String.format("%02d:%02d", min, sec)
            }
        }
    }

    private fun stopVideoRecordingUi() {
        binding.btnShutter.setImageResource(R.drawable.bg_shutter_video)
        binding.videoRecordingBanner.visibility = View.GONE
        videoPaused = false
        binding.btnVideoPause.visibility = View.GONE
        binding.btnVideoSnapshot.visibility = View.GONE
        binding.cardGalleryThumbnail.visibility = View.VISIBLE
        binding.btnSwitchCamera.visibility = View.VISIBLE
        videoTimerJob?.cancel()
        videoTimerJob = null
        recordingDotAnimator?.cancel()
        recordingDotAnimator = null
        binding.viewRecordingDot.alpha = 1f
    }

    override fun onDestroy() {
        super.onDestroy()
        reticleAutoHideJob?.cancel()
        reticleAutoHideJob = null
        stopVideoRecordingUi()
        slowMotion.release()
        if (::cameraManager.isInitialized) cameraManager.release()
    }

    companion object {
        private const val KEY_GC_LOOK_APPLIED = "gc_look_applied"
        private const val HINT_DURATION_MS = 3000L
        private const val LOW_LIGHT_POLL_MS = 700L
        private const val QR_RESULT_MS = 4000L
        private const val MAX_HOLD_BURST = 30
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        // Asked for on first launch, but the camera works without them.
        private val OPTIONAL_PERMISSIONS = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        private const val ASTRO_MAX_LUX = 10f
        private const val MAIN_SENSOR_LABEL = "12MP MAIN"
        private const val FRONT_SENSOR_LABEL = "8MP FRONT"
    }
}
