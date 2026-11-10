package com.motorola.pixelcamera.ui

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
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
import com.motorola.pixelcamera.R
import com.motorola.pixelcamera.core.CameraManager
import com.motorola.pixelcamera.core.CameraMode
import com.motorola.pixelcamera.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    private var topShotMode = 1   // 0: Off, 1: Auto, 2: On
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

    // Gyroscope / Accelerometer Horizon Level Indicator
    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null
    private var filteredRoll = 0f
    private var isLevelAligned = false

    // Pro Manual Controls State
    enum class ProTab { ISO, SHUTTER, FOCUS, WB, EV }
    private var activeProTab = ProTab.ISO

    private var proIsoIndex = 0
    private var proShutterIndex = 0
    private var proFocusProgress = 0
    private var proWbIndex = 0

    private val isoValues = intArrayOf(0, 50, 100, 200, 400, 800, 1600, 3200, 6400)
    private val shutterLabels = arrayOf("AUTO", "1/4000s", "1/2000s", "1/1000s", "1/500s", "1/250s", "1/125s", "1/60s", "1/30s", "1/15s", "1/8s", "1/4s", "1/2s", "1s")
    private val shutterNanos = longArrayOf(0L, 250_000L, 500_000L, 1_000_000L, 2_000_000L, 4_000_000L, 8_000_000L, 16_666_666L, 33_333_333L, 66_666_666L, 125_000_000L, 250_000_000L, 500_000_000L, 1_000_000_000L)
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

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            if (event == null || event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

            val ax = event.values[0]
            val ay = event.values[1]
            val az = event.values[2]

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
                    binding.tvHorizonAngle.text = "0°"

                    val goldColor = ContextCompat.getColor(this@MainActivity, R.color.pixel_accent_yellow)
                    binding.horizonCrosshairH.setBackgroundColor(goldColor)
                    binding.horizonCrosshairV.setBackgroundColor(goldColor)
                    binding.horizonFloatingCrosshairH.setBackgroundColor(goldColor)
                    binding.horizonFloatingCrosshairV.setBackgroundColor(goldColor)
                    binding.tvHorizonAngle.setTextColor(goldColor)

                    if (!isLevelAligned) {
                        isLevelAligned = true
                        binding.horizonLevelContainer.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    }
                } else {
                    isLevelAligned = false
                    binding.horizonFloatingReticle.translationX = targetDx
                    binding.horizonFloatingReticle.translationY = targetDy

                    val deg = kotlin.math.round(Math.toDegrees(atan2(distance.toDouble(), (9.8 * 6f * density)))).toInt()
                    binding.tvHorizonAngle.text = "${deg}°"

                    val tiltColor = Color.parseColor("#CCFFFFFF")
                    val goldColor = ContextCompat.getColor(this@MainActivity, R.color.pixel_accent_yellow)
                    binding.horizonCrosshairH.setBackgroundColor(goldColor)
                    binding.horizonCrosshairV.setBackgroundColor(goldColor)
                    binding.horizonFloatingCrosshairH.setBackgroundColor(tiltColor)
                    binding.horizonFloatingCrosshairV.setBackgroundColor(tiltColor)
                    binding.tvHorizonAngle.setTextColor(tiltColor)
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
                    binding.tvHorizonAngle.text = "0°"

                    val goldColor = ContextCompat.getColor(this@MainActivity, R.color.pixel_accent_yellow)
                    binding.horizonWingLeft.setBackgroundColor(goldColor)
                    binding.horizonWingRight.setBackgroundColor(goldColor)
                    binding.horizonCrosshairH.setBackgroundColor(goldColor)
                    binding.horizonCrosshairV.setBackgroundColor(goldColor)
                    binding.tvHorizonAngle.setTextColor(goldColor)

                    if (!isLevelAligned) {
                        isLevelAligned = true
                        binding.horizonLevelContainer.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    }
                } else {
                    isLevelAligned = false
                    binding.horizonRotatingBar.rotation = -filteredRoll
                    val deg = absDelta.roundToInt()
                    binding.tvHorizonAngle.text = "${deg}°"

                    val tiltColor = Color.parseColor("#CCFFFFFF")
                    val goldColor = ContextCompat.getColor(this@MainActivity, R.color.pixel_accent_yellow)
                    binding.horizonWingLeft.setBackgroundColor(tiltColor)
                    binding.horizonWingRight.setBackgroundColor(tiltColor)
                    binding.horizonCrosshairH.setBackgroundColor(goldColor)
                    binding.horizonCrosshairV.setBackgroundColor(goldColor)
                    binding.tvHorizonAngle.setTextColor(tiltColor)
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

        prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)
        cameraManager = CameraManager(this)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

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
                val newZoom = (currentZoom * scale).coerceIn(1.0f, 8.0f)
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
        })

        setupListeners()
        initDefaultUiState()

        if (allPermissionsGranted()) {
            setupCamera()
            loadLatestGalleryThumbnail()
        } else {
            permissionLauncher.launch(REQUIRED_PERMISSIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        syncSettings()
        loadLatestGalleryThumbnail()
        accelerometer?.let {
            sensorManager?.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager?.unregisterListener(sensorListener)
    }

    private fun initDefaultUiState() {
        // Ensure Photo mode is default with warm sand pill
        selectModeUi(binding.modeCamera)
        updatePhotoVideoToggle(isVideo = false)
        selectZoomPill(binding.btnZoom1x)
        binding.overlayPortraitAperture.visibility = View.GONE
        binding.proControlPanel.visibility = View.GONE
        binding.histogramView.visibility = View.GONE
        binding.quickSettingsContainer.visibility = View.GONE
        binding.focusReticleContainer.visibility = View.GONE
        binding.tvExposureEvBadge.visibility = View.GONE
        selectProTab(ProTab.ISO)
    }

    private fun syncSettings() {
        val rawPref = prefs.getBoolean(SettingsActivity.KEY_RAW_ENABLED, false)
        cameraManager.isRawEnabled = rawPref

        val remosaicPref = prefs.getBoolean(SettingsActivity.KEY_108MP_REMOSAIC, false)
        cameraManager.is108MpEnabled = remosaicPref
        binding.tvSensorInfo.text = if (remosaicPref) "108MP ULTRA" else "108MP HM2"

        val flashPref = prefs.getInt(SettingsActivity.KEY_FLASH_MODE, ImageCapture.FLASH_MODE_OFF)
        cameraManager.flashMode = flashPref
        moreLightMode = when (flashPref) {
            ImageCapture.FLASH_MODE_ON -> 2
            ImageCapture.FLASH_MODE_AUTO -> 1
            else -> 0
        }
        updateFlashUi(flashPref)
        updateQuickSettingsUi()
    }

    private fun updateQuickSettingsUi() {
        // 1. More light row
        when (moreLightMode) {
            0 -> {
                binding.tvQsLightSubtitle.text = "None"
                setQsButtonActive(binding.btnQsLightNone, true)
                setQsButtonActive(binding.btnQsLightNight, false)
                setQsButtonActive(binding.btnQsLightFlash, false)
            }
            1 -> {
                binding.tvQsLightSubtitle.text = "Night Sight Auto"
                setQsButtonActive(binding.btnQsLightNone, false)
                setQsButtonActive(binding.btnQsLightNight, true)
                setQsButtonActive(binding.btnQsLightFlash, false)
            }
            2 -> {
                binding.tvQsLightSubtitle.text = "Flash On"
                setQsButtonActive(binding.btnQsLightNone, false)
                setQsButtonActive(binding.btnQsLightNight, false)
                setQsButtonActive(binding.btnQsLightFlash, true)
            }
        }

        // 2. RAW/JPEG row
        val isRaw = cameraManager.isRawEnabled
        binding.tvQsRawSubtitle.text = if (isRaw) "RAW + JPEG" else "JPEG only"
        setQsButtonActive(binding.btnQsRawOff, !isRaw)
        setQsButtonActive(binding.btnQsRawOn, isRaw)

        // 3. Top Shot row
        when (topShotMode) {
            0 -> {
                binding.tvQsTopShotSubtitle.text = "Off"
                setQsButtonActive(binding.btnQsTopShotOff, true)
                setQsButtonActive(binding.btnQsTopShotAuto, false)
                setQsButtonActive(binding.btnQsTopShotOn, false)
            }
            1 -> {
                binding.tvQsTopShotSubtitle.text = "Auto ⓘ"
                setQsButtonActive(binding.btnQsTopShotOff, false)
                setQsButtonActive(binding.btnQsTopShotAuto, true)
                setQsButtonActive(binding.btnQsTopShotOn, false)
            }
            2 -> {
                binding.tvQsTopShotSubtitle.text = "On"
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

        // 5. Resolution row
        val is108 = cameraManager.is108MpEnabled
        binding.tvQsResSubtitle.text = if (is108) "108 MP Ultra" else "12 MP Binned"
        setQsButtonActive(binding.btnQsRes12, !is108)
        setQsButtonActive(binding.btnQsRes108, is108)

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
        val activeTextColor = ContextCompat.getColor(this, R.color.pixel_pill_text_active)
        val inactiveTextColor = ContextCompat.getColor(this, R.color.pixel_text_white)

        binding.btnQsGridOff.setBackgroundResource(if (gridMode == 0) activeBg else inactiveBg)
        binding.btnQsGridOff.setTextColor(if (gridMode == 0) activeTextColor else inactiveTextColor)

        binding.btnQsGrid3x3.setBackgroundResource(if (gridMode == 1) activeBg else inactiveBg)
        binding.btnQsGrid3x3.setTextColor(if (gridMode == 1) activeTextColor else inactiveTextColor)

        binding.btnQsGridGolden.setBackgroundResource(if (gridMode == 2) activeBg else inactiveBg)
        binding.btnQsGridGolden.setTextColor(if (gridMode == 2) activeTextColor else inactiveTextColor)
    }

    private fun setQsButtonActive(button: ImageView, active: Boolean) {
        if (active) {
            button.setBackgroundResource(R.drawable.bg_qs_btn_active)
            ImageViewCompat.setImageTintList(button, ContextCompat.getColorStateList(this, R.color.pixel_icon_active))
        } else {
            button.setBackgroundResource(R.drawable.bg_qs_btn_inactive)
            ImageViewCompat.setImageTintList(button, ContextCompat.getColorStateList(this, R.color.pixel_text_secondary))
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
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Failed to start camera: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadLatestGalleryThumbnail() {
        lifecycleScope.launch(Dispatchers.IO) {
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DATE_ADDED
            )
            val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
            val queryUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

            try {
                contentResolver.query(queryUri, projection, null, null, sortOrder)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                        val id = cursor.getLong(idColumn)
                        val contentUri = ContentUris.withAppendedId(queryUri, id)

                        val thumbnail = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            contentResolver.loadThumbnail(contentUri, Size(120, 120), null)
                        } else {
                            @Suppress("DEPRECATION")
                            MediaStore.Images.Thumbnails.getThumbnail(
                                contentResolver, id,
                                MediaStore.Images.Thumbnails.MINI_KIND, null
                            )
                        }
                        if (thumbnail != null) {
                            runOnUiThread {
                                binding.ivGalleryThumbnail.setImageBitmap(thumbnail)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // Ignore if permission not granted yet or no photos exist
            }
        }
    }

    private fun setupListeners() {
        // Bottom-left Settings button toggles the Quick Settings Sheet
        binding.btnBottomSettings.setOnClickListener {
            toggleQuickSettings()
        }

        // Tapping viewfinder when Quick Settings is open closes it
        binding.viewFinder.setOnClickListener {
            if (binding.quickSettingsContainer.visibility == View.VISIBLE) {
                toggleQuickSettings()
            }
        }

        // "More settings" floating pill button opens full SettingsActivity
        binding.btnMoreSettings.setOnClickListener {
            binding.quickSettingsContainer.visibility = View.GONE
            startActivity(Intent(this, SettingsActivity::class.java))
        }

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

        // Quick Settings - Row 2: RAW / JPEG
        binding.btnQsRawOff.setOnClickListener { setQuickRaw(false) }
        binding.btnQsRawOn.setOnClickListener { setQuickRaw(true) }

        // Quick Settings - Row 3: HDR+ (Off / Auto / Enhanced)
        binding.btnQsTopShotOff.setOnClickListener {
            topShotMode = 0
            cameraManager.hdrPlusMode = 0
            updateQuickSettingsUi()
            Toast.makeText(this, "HDR+ Off", Toast.LENGTH_SHORT).show()
        }
        binding.btnQsTopShotAuto.setOnClickListener {
            topShotMode = 1
            cameraManager.hdrPlusMode = 1
            updateQuickSettingsUi()
            Toast.makeText(this, "HDR+ Auto", Toast.LENGTH_SHORT).show()
        }
        binding.btnQsTopShotOn.setOnClickListener {
            topShotMode = 2
            cameraManager.hdrPlusMode = 2
            updateQuickSettingsUi()
            Toast.makeText(this, "HDR+ Enhanced", Toast.LENGTH_SHORT).show()
        }

        // Quick Settings - Row 4: Timer
        binding.btnQsTimerOff.setOnClickListener {
            timerDuration = 0
            updateQuickSettingsUi()
            Toast.makeText(this, "Timer Off", Toast.LENGTH_SHORT).show()
        }
        binding.btnQsTimer3s.setOnClickListener {
            timerDuration = 3
            updateQuickSettingsUi()
            Toast.makeText(this, "Timer 3s", Toast.LENGTH_SHORT).show()
        }
        binding.btnQsTimer10s.setOnClickListener {
            timerDuration = 10
            updateQuickSettingsUi()
            Toast.makeText(this, "Timer 10s", Toast.LENGTH_SHORT).show()
        }

        // Quick Settings - Row 6: Grid Type (Off / 3x3 / Golden Ratio)
        binding.btnQsGridOff.setOnClickListener {
            setGridType(0)
            Toast.makeText(this, "Grid Off", Toast.LENGTH_SHORT).show()
        }
        binding.btnQsGrid3x3.setOnClickListener {
            setGridType(1)
            Toast.makeText(this, "Grid 3×3", Toast.LENGTH_SHORT).show()
        }
        binding.btnQsGridGolden.setOnClickListener {
            setGridType(2)
            Toast.makeText(this, "Grid Golden Ratio", Toast.LENGTH_SHORT).show()
        }

        // MGC 9.7 Segmented Tabs: [ General | Pro ]
        binding.tabQsGeneral.setOnClickListener {
            binding.tabQsGeneral.setBackgroundResource(R.drawable.bg_segmented_pill_active)
            binding.tabQsGeneral.setTextColor(ContextCompat.getColor(this, R.color.pixel_pill_text_active))
            binding.tabQsGeneral.paint.isFakeBoldText = true

            binding.tabQsPro.background = null
            binding.tabQsPro.setTextColor(ContextCompat.getColor(this, R.color.pixel_text_white))
            binding.tabQsPro.paint.isFakeBoldText = false

            binding.containerQsGeneral.visibility = View.VISIBLE
            binding.containerQsPro.visibility = View.GONE
        }

        binding.tabQsPro.setOnClickListener {
            binding.tabQsPro.setBackgroundResource(R.drawable.bg_segmented_pill_active)
            binding.tabQsPro.setTextColor(ContextCompat.getColor(this, R.color.pixel_pill_text_active))
            binding.tabQsPro.paint.isFakeBoldText = true

            binding.tabQsGeneral.background = null
            binding.tabQsGeneral.setTextColor(ContextCompat.getColor(this, R.color.pixel_text_white))
            binding.tabQsGeneral.paint.isFakeBoldText = false

            binding.containerQsGeneral.visibility = View.GONE
            binding.containerQsPro.visibility = View.VISIBLE
        }

        // Pro Tab: Lens Selection (Auto vs Manual)
        binding.btnQsLensAuto.setOnClickListener {
            binding.btnQsLensAuto.setBackgroundResource(R.drawable.bg_segmented_pill_active)
            binding.btnQsLensAuto.setTextColor(ContextCompat.getColor(this, R.color.pixel_pill_text_active))
            binding.btnQsLensManual.setBackgroundResource(R.drawable.bg_qs_btn_inactive)
            binding.btnQsLensManual.setTextColor(ContextCompat.getColor(this, R.color.pixel_text_white))
            binding.tvQsLensSubtitle.text = "Auto"
        }

        binding.btnQsLensManual.setOnClickListener {
            binding.btnQsLensManual.setBackgroundResource(R.drawable.bg_segmented_pill_active)
            binding.btnQsLensManual.setTextColor(ContextCompat.getColor(this, R.color.pixel_pill_text_active))
            binding.btnQsLensAuto.setBackgroundResource(R.drawable.bg_qs_btn_inactive)
            binding.btnQsLensAuto.setTextColor(ContextCompat.getColor(this, R.color.pixel_text_white))
            binding.tvQsLensSubtitle.text = "Manual"
        }

        // Quick Settings - Row 4: Aspect Ratio
        binding.btnQsRatio43.setOnClickListener {
            cameraManager.aspectRatio = AspectRatio.RATIO_4_3
            cameraManager.bindCameraUseCases()
            updateQuickSettingsUi()
            Toast.makeText(this, "Aspect Ratio: 4:3 Standard", Toast.LENGTH_SHORT).show()
        }
        binding.btnQsRatio169.setOnClickListener {
            cameraManager.aspectRatio = AspectRatio.RATIO_16_9
            cameraManager.bindCameraUseCases()
            updateQuickSettingsUi()
            Toast.makeText(this, "Aspect Ratio: 16:9 Widescreen", Toast.LENGTH_SHORT).show()
        }

        // Quick Settings - Row 5: Resolution
        binding.btnQsRes12.setOnClickListener { setQuickResolution(false) }
        binding.btnQsRes108.setOnClickListener { setQuickResolution(true) }

        // Quick Settings - Row 6: Google AWB
        binding.btnQsAwbOff.setOnClickListener {
            cameraManager.isGoogleAwbEnabled = false
            cameraManager.bindCameraUseCases()
            updateQuickSettingsUi()
            Toast.makeText(this, "Google AWB: Standard", Toast.LENGTH_SHORT).show()
        }
        binding.btnQsAwbOn.setOnClickListener {
            cameraManager.isGoogleAwbEnabled = true
            cameraManager.bindCameraUseCases()
            updateQuickSettingsUi()
            Toast.makeText(this, "Google AWB: ON", Toast.LENGTH_SHORT).show()
        }

        // Quick Settings - Floating "More settings" Pill
        binding.btnMoreSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
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
        binding.btnProControls.setOnClickListener {
            val isShowing = binding.proControlPanel.visibility == View.VISIBLE
            if (isShowing) {
                binding.proControlPanel.visibility = View.GONE
                binding.histogramView.visibility = View.GONE
                binding.btnProControls.setBackgroundResource(R.drawable.bg_settings_circle)
                ImageViewCompat.setImageTintList(binding.btnProControls, ContextCompat.getColorStateList(this, R.color.pixel_text_white))
            } else {
                binding.proControlPanel.visibility = View.VISIBLE
                binding.histogramView.visibility = View.VISIBLE
                binding.btnProControls.setBackgroundResource(R.drawable.bg_qs_btn_active)
                ImageViewCompat.setImageTintList(binding.btnProControls, ContextCompat.getColorStateList(this, R.color.pixel_icon_active))
            }
        }

        // Tier 1: Shutter Button
        binding.btnShutter.setOnClickListener {
            onShutterClicked()
        }

        // Tier 1: Camera Switch (Front <-> Rear)
        binding.btnSwitchCamera.setOnClickListener {
            flipCameraWithAnimation()
        }

        // MGC 9.7 Dual Zoom Selectors (Upper Physical Lenses + Lower Multipliers)
        binding.btnZoomUltrawide.setOnClickListener {
            cameraManager.switchToLens(CameraManager.CAMERA_ULTRAWIDE, 1.0f)
            updateDualZoomUi("0.6", "1×")
            binding.tvSensorInfo.text = "8MP UW"
        }

        binding.btnLens10.setOnClickListener {
            cameraManager.switchToLens(CameraManager.CAMERA_MAIN, 1.0f)
            updateDualZoomUi("1.0", "1×")
            binding.tvSensorInfo.text = if (cameraManager.is108MpEnabled) "108MP ULTRA" else "108MP HM2"
        }

        binding.btnLens20.setOnClickListener {
            cameraManager.switchToLens(CameraManager.CAMERA_MAIN, 2.0f)
            updateDualZoomUi("2.0", "2")
            binding.tvSensorInfo.text = "2X SENSOR CROP"
        }

        binding.btnZoom1x.setOnClickListener {
            cameraManager.switchToLens(CameraManager.CAMERA_MAIN, 1.0f)
            updateDualZoomUi("1.0", "1×")
            binding.tvSensorInfo.text = if (cameraManager.is108MpEnabled) "108MP ULTRA" else "108MP HM2"
        }

        binding.btnZoom2x.setOnClickListener {
            cameraManager.switchToLens(CameraManager.CAMERA_MAIN, 2.0f)
            updateDualZoomUi("2.0", "2")
            binding.tvSensorInfo.text = "2X SUPER RES"
        }

        binding.btnZoom5x.setOnClickListener {
            cameraManager.switchToLens(CameraManager.CAMERA_MAIN, 5.0f)
            updateDualZoomUi("2.0", "5")
            binding.tvSensorInfo.text = "5X ZOOM"
        }

        // Tier 2: Mode Ribbon / Carousel
        binding.modeLongExposure.setOnClickListener {
            setMode(CameraMode.CAMERA, binding.modeLongExposure)
            Toast.makeText(this, "Long Exposure • Handheld Stabilization", Toast.LENGTH_SHORT).show()
        }
        binding.modePortrait.setOnClickListener { setMode(CameraMode.PORTRAIT, binding.modePortrait) }
        binding.modeCamera.setOnClickListener { setMode(CameraMode.CAMERA, binding.modeCamera) }
        binding.modeNightSight.setOnClickListener { setMode(CameraMode.NIGHT_SIGHT, binding.modeNightSight) }
        binding.modePanorama.setOnClickListener {
            setMode(CameraMode.CAMERA, binding.modePanorama)
            Toast.makeText(this, "Panorama Mode", Toast.LENGTH_SHORT).show()
        }
        binding.modePro.setOnClickListener { setMode(CameraMode.PRO, binding.modePro) }

        // Portrait Aperture Slider (f/1.4 - f/16)
        binding.sbAperture.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                portraitBlurProgress = progress
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

        binding.sbProSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                when (activeProTab) {
                    ProTab.ISO -> {
                        proIsoIndex = progress
                        updateProUiForIso()
                        cameraManager.setManualIso(isoValues[proIsoIndex])
                    }
                    ProTab.SHUTTER -> {
                        proShutterIndex = progress
                        updateProUiForShutter()
                        cameraManager.setManualShutter(shutterNanos[proShutterIndex])
                    }
                    ProTab.FOCUS -> {
                        proFocusProgress = progress
                        updateProUiForFocus()
                        if (progress == 0) {
                            cameraManager.setManualFocus(-1f)
                        } else {
                            val diopters = (progress / 100f) * 10.0f
                            cameraManager.setManualFocus(diopters)
                        }
                    }
                    ProTab.WB -> {
                        proWbIndex = progress
                        updateProUiForWb()
                        cameraManager.setManualWb(wbModes[proWbIndex])
                    }
                    ProTab.EV -> {
                        val range = cameraManager.getExposureCompensationRange()
                        val targetEv = range.lower + progress
                        currentEvIndex = targetEv
                        cameraManager.setExposureCompensation(targetEv)
                        updateProUiForEv()
                    }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.btnProReset.setOnClickListener {
            when (activeProTab) {
                ProTab.ISO -> {
                    proIsoIndex = 0
                    binding.sbProSlider.progress = 0
                    updateProUiForIso()
                    cameraManager.setManualIso(0)
                }
                ProTab.SHUTTER -> {
                    proShutterIndex = 0
                    binding.sbProSlider.progress = 0
                    updateProUiForShutter()
                    cameraManager.setManualShutter(0L)
                }
                ProTab.FOCUS -> {
                    proFocusProgress = 0
                    binding.sbProSlider.progress = 0
                    updateProUiForFocus()
                    cameraManager.setManualFocus(-1f)
                }
                ProTab.WB -> {
                    proWbIndex = 0
                    binding.sbProSlider.progress = 0
                    updateProUiForWb()
                    cameraManager.setManualWb(CaptureRequest.CONTROL_AWB_MODE_AUTO)
                }
                ProTab.EV -> {
                    currentEvIndex = 0
                    val range = cameraManager.getExposureCompensationRange()
                    binding.sbProSlider.progress = (0 - range.lower).coerceIn(0, binding.sbProSlider.max)
                    cameraManager.setExposureCompensation(0)
                    updateProUiForEv()
                }
            }
        }

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
            val intent = Intent(Intent.ACTION_VIEW).apply {
                if (latestCapturedUri != null) {
                    setDataAndType(latestCapturedUri, "image/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } else {
                    type = "image/*"
                }
            }
            try {
                startActivity(intent)
            } catch (e: Exception) {
                // Ignore if no gallery app available
            }
        }
    }

    private fun toggleQuickSettings() {
        if (binding.quickSettingsContainer.visibility == View.VISIBLE) {
            binding.quickSettingsContainer.animate()
                .alpha(0f)
                .translationY(-40f)
                .setDuration(180)
                .withEndAction {
                    binding.quickSettingsContainer.visibility = View.GONE
                }
                .start()
        } else {
            updateQuickSettingsUi()
            binding.quickSettingsContainer.alpha = 0f
            binding.quickSettingsContainer.translationY = -40f
            binding.quickSettingsContainer.visibility = View.VISIBLE
            binding.quickSettingsContainer.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(220)
                .start()
        }
    }

    private fun selectProTab(tab: ProTab) {
        activeProTab = tab
        val activeBg = R.drawable.bg_zoom_pill_active
        val activeColor = ContextCompat.getColor(this, R.color.pixel_pill_text_active)
        val inactiveColor = ContextCompat.getColor(this, R.color.pixel_text_secondary)

        val tabs = listOf(
            binding.tabProIso,
            binding.tabProShutter,
            binding.tabProFocus,
            binding.tabProWb,
            binding.tabProEv
        )
        tabs.forEach {
            it.background = null
            it.setTextColor(inactiveColor)
        }

        when (tab) {
            ProTab.ISO -> {
                binding.tabProIso.setBackgroundResource(activeBg)
                binding.tabProIso.setTextColor(activeColor)
                binding.sbProSlider.max = isoValues.size - 1
                binding.sbProSlider.progress = proIsoIndex
                updateProUiForIso()
            }
            ProTab.SHUTTER -> {
                binding.tabProShutter.setBackgroundResource(activeBg)
                binding.tabProShutter.setTextColor(activeColor)
                binding.sbProSlider.max = shutterLabels.size - 1
                binding.sbProSlider.progress = proShutterIndex
                updateProUiForShutter()
            }
            ProTab.FOCUS -> {
                binding.tabProFocus.setBackgroundResource(activeBg)
                binding.tabProFocus.setTextColor(activeColor)
                binding.sbProSlider.max = 100
                binding.sbProSlider.progress = proFocusProgress
                updateProUiForFocus()
            }
            ProTab.WB -> {
                binding.tabProWb.setBackgroundResource(activeBg)
                binding.tabProWb.setTextColor(activeColor)
                binding.sbProSlider.max = wbLabels.size - 1
                binding.sbProSlider.progress = proWbIndex
                updateProUiForWb()
            }
            ProTab.EV -> {
                binding.tabProEv.setBackgroundResource(activeBg)
                binding.tabProEv.setTextColor(activeColor)
                val range = cameraManager.getExposureCompensationRange()
                binding.sbProSlider.max = (range.upper - range.lower).coerceAtLeast(1)
                binding.sbProSlider.progress = (currentEvIndex - range.lower).coerceIn(0, binding.sbProSlider.max)
                updateProUiForEv()
            }
        }
    }

    private fun updateProUiForIso() {
        binding.tvProParamValue.text = if (proIsoIndex == 0) "ISO AUTO" else "ISO ${isoValues[proIsoIndex]}"
    }

    private fun updateProUiForShutter() {
        binding.tvProParamValue.text = shutterLabels[proShutterIndex]
    }

    private fun updateProUiForFocus() {
        binding.tvProParamValue.text = if (proFocusProgress == 0) "FOCUS AF" else if (proFocusProgress > 80) "Macro" else if (proFocusProgress < 20) "Infinity" else "Focus ${proFocusProgress}%"
    }

    private fun updateProUiForWb() {
        binding.tvProParamValue.text = wbLabels[proWbIndex].split(" ").first()
    }

    private fun updateProUiForEv() {
        val ev = currentEvIndex * cameraManager.getExposureCompensationStep()
        binding.tvProParamValue.text = String.format("EV %+.1f", ev)
    }

    private fun flipCameraWithAnimation() {
        binding.btnSwitchCamera.animate().rotationBy(180f).setDuration(300).start()
        cameraManager.switchCamera()
        val isFront = cameraManager.isFrontCamera()
        binding.zoomPillContainer.visibility = if (isFront) View.GONE else View.VISIBLE
        if (!isFront) {
            selectZoomPill(binding.btnZoom1x)
        }
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
        val activeBg = R.drawable.bg_qs_btn_active
        val inactiveBg = R.drawable.bg_qs_btn_inactive
        val activeTextColor = ContextCompat.getColor(this, R.color.pixel_pill_text_active)
        val inactiveTextColor = ContextCompat.getColor(this, R.color.pixel_text_white)

        binding.btnQsGridOff.setBackgroundResource(if (mode == 0) activeBg else inactiveBg)
        binding.btnQsGridOff.setTextColor(if (mode == 0) activeTextColor else inactiveTextColor)

        binding.btnQsGrid3x3.setBackgroundResource(if (mode == 1) activeBg else inactiveBg)
        binding.btnQsGrid3x3.setTextColor(if (mode == 1) activeTextColor else inactiveTextColor)

        binding.btnQsGridGolden.setBackgroundResource(if (mode == 2) activeBg else inactiveBg)
        binding.btnQsGridGolden.setTextColor(if (mode == 2) activeTextColor else inactiveTextColor)

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
    }

    private fun switchToVideoMode() {
        if (currentMode != CameraMode.VIDEO) {
            setMode(CameraMode.VIDEO, binding.modeVideo)
        }
        updatePhotoVideoToggle(isVideo = true)
    }

    private fun updatePhotoVideoToggle(isVideo: Boolean) {
        if (isVideo) {
            binding.btnToggleVideo.setBackgroundResource(R.drawable.bg_toggle_item_active)
            ImageViewCompat.setImageTintList(binding.btnToggleVideo, ContextCompat.getColorStateList(this, R.color.pixel_icon_active))

            binding.btnToggleCamera.background = null
            ImageViewCompat.setImageTintList(binding.btnToggleCamera, ContextCompat.getColorStateList(this, R.color.pixel_text_white))
        } else {
            binding.btnToggleCamera.setBackgroundResource(R.drawable.bg_toggle_item_active)
            ImageViewCompat.setImageTintList(binding.btnToggleCamera, ContextCompat.getColorStateList(this, R.color.pixel_icon_active))

            binding.btnToggleVideo.background = null
            ImageViewCompat.setImageTintList(binding.btnToggleVideo, ContextCompat.getColorStateList(this, R.color.pixel_text_white))
        }
    }

    private fun setQuickFlash(mode: Int) {
        cameraManager.flashMode = mode
        prefs.edit().putInt(SettingsActivity.KEY_FLASH_MODE, mode).apply()
        updateFlashUi(mode)
        updateQuickSettingsUi()
    }

    private fun setQuickRaw(enabled: Boolean) {
        cameraManager.isRawEnabled = enabled
        prefs.edit().putBoolean(SettingsActivity.KEY_RAW_ENABLED, enabled).apply()
        syncSettings()
        updateQuickSettingsUi()
        Toast.makeText(this, if (enabled) "RAW (DNG) enabled" else "JPEG only", Toast.LENGTH_SHORT).show()
    }

    private fun setQuickResolution(is108Mp: Boolean) {
        cameraManager.is108MpEnabled = is108Mp
        prefs.edit().putBoolean(SettingsActivity.KEY_108MP_REMOSAIC, is108Mp).apply()
        syncSettings()
        updateQuickSettingsUi()
        cameraManager.bindCameraUseCases()
        Toast.makeText(this, if (is108Mp) "108MP Remosaic ON" else "12MP Nona-binning ON", Toast.LENGTH_SHORT).show()
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

    private fun selectModeUi(selectedView: TextView) {
        val modeViews = listOf(
            binding.modeLongExposure,
            binding.modePortrait,
            binding.modeCamera,
            binding.modeNightSight,
            binding.modePanorama,
            binding.modePro
        )

        for (v in modeViews) {
            if (v == selectedView) {
                v.setBackgroundResource(R.drawable.bg_mode_pill_active)
                v.setTextColor(ContextCompat.getColor(this, R.color.pixel_accent_sand_text))
                v.paint.isFakeBoldText = true
            } else {
                v.background = null
                v.setTextColor(ContextCompat.getColor(this, R.color.pixel_text_white))
                v.paint.isFakeBoldText = false
            }
        }
    }

    private fun setMode(mode: CameraMode, selectedView: TextView) {
        if (currentMode == mode) return

        // If recording video when switching modes, stop it first
        if (cameraManager.isRecordingVideo()) {
            stopVideoRecordingUi()
            cameraManager.stopVideoRecording()
        }

        currentMode = mode
        cameraManager.currentCameraMode = mode

        selectModeUi(selectedView)

        // Update Tier 3 Toggle Pill
        updatePhotoVideoToggle(isVideo = (mode == CameraMode.VIDEO))

        // Update Pixel Shutter color per mode
        when (mode) {
            CameraMode.NIGHT_SIGHT -> binding.btnShutter.setImageResource(R.drawable.bg_shutter_night)
            CameraMode.VIDEO -> binding.btnShutter.setImageResource(R.drawable.bg_shutter_video)
            else -> binding.btnShutter.setImageResource(R.drawable.bg_shutter_button)
        }

        // Update Overlays Visibility per Mode
        when (mode) {
            CameraMode.NIGHT_SIGHT -> {
                binding.tvHdrBadge.text = "NIGHT SIGHT"
                binding.proControlPanel.visibility = View.GONE
                binding.histogramView.visibility = View.GONE
                binding.overlayPortraitAperture.visibility = View.GONE
            }
            CameraMode.PORTRAIT -> {
                binding.tvHdrBadge.text = "PORTRAIT DEPTH"
                binding.proControlPanel.visibility = View.GONE
                binding.histogramView.visibility = View.GONE
                binding.overlayPortraitAperture.visibility = View.VISIBLE
            }
            CameraMode.PRO -> {
                binding.tvHdrBadge.text = "PRO MANUAL"
                binding.proControlPanel.visibility = View.VISIBLE
                binding.histogramView.visibility = View.VISIBLE
                binding.overlayPortraitAperture.visibility = View.GONE
            }
            CameraMode.VIDEO -> {
                binding.tvHdrBadge.text = "FHD VIDEO"
                binding.proControlPanel.visibility = View.GONE
                binding.histogramView.visibility = View.GONE
                binding.overlayPortraitAperture.visibility = View.GONE
            }
            CameraMode.CAMERA -> {
                binding.tvHdrBadge.text = "HDR+ ON"
                binding.proControlPanel.visibility = View.GONE
                binding.histogramView.visibility = View.GONE
                binding.overlayPortraitAperture.visibility = View.GONE
            }
        }

        // Rebind use cases for optimal performance in active mode
        cameraManager.bindCameraUseCases()
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

        if (isCapturing) return

        if (timerDuration > 0) {
            lifecycleScope.launch {
                for (i in timerDuration downTo 1) {
                    Toast.makeText(this@MainActivity, "$i...", Toast.LENGTH_SHORT).show()
                    delay(1000)
                }
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

        // If Night Sight is active, show the countdown overlay
        if (currentMode == CameraMode.NIGHT_SIGHT) {
            binding.overlayNightHoldStill.visibility = View.VISIBLE
            binding.tvNightTimer.text = "Hold still..."
        } else {
            binding.processingPill.visibility = View.VISIBLE
            binding.tvProcessingStatus.text = "Capturing..."
        }
        binding.progressThumbnailSpin.visibility = View.VISIBLE

        lifecycleScope.launch {
            val blurStrength = 2f + (portraitBlurProgress / 100f) * 24f
            val uri: Uri? = cameraManager.capturePhoto(currentMode, blurStrength) { status ->
                runOnUiThread {
                    binding.tvProcessingStatus.text = status
                }
            }

            binding.processingPill.visibility = View.GONE
            binding.overlayNightHoldStill.visibility = View.GONE
            binding.progressThumbnailSpin.visibility = View.GONE
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
                Toast.makeText(this@MainActivity, "Photo saved to Gallery", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@MainActivity, "Capture failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun handleVideoShutter() {
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
                                Toast.makeText(this, "Video saved to Gallery", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(this, "Video record error: ${event.error}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
            if (started) {
                startVideoRecordingUi()
            } else {
                Toast.makeText(this, "Failed to start recording", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startVideoRecordingUi() {
        binding.btnShutter.setImageResource(R.drawable.bg_shutter_video_recording)
        binding.videoRecordingBanner.visibility = View.VISIBLE

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
    }

    companion object {
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
    }
}
