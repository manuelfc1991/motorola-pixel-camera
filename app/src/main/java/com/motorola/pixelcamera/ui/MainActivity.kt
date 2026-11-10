package com.motorola.pixelcamera.ui

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.ImageCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
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

    // Video Recording Timer & Animation Jobs
    private var videoTimerJob: Job? = null
    private var recordingDotAnimator: ObjectAnimator? = null
    private var recordingSeconds = 0

    // Aperture blur strength for Portrait mode (progress 0..100)
    private var portraitBlurProgress = 50

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

        // Live Histogram callback for Pro Mode
        cameraManager.onHistogramUpdate = { r, g, b ->
            if (currentMode == CameraMode.PRO) {
                runOnUiThread {
                    binding.histogramView.updateHistogram(r, g, b)
                }
            }
        }

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

        // 4. Timer row
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

        // 5. Resolution row
        val is108 = cameraManager.is108MpEnabled
        binding.tvQsResSubtitle.text = if (is108) "108 MP Ultra" else "12 MP Binned"
        setQsButtonActive(binding.btnQsRes12, !is108)
        setQsButtonActive(binding.btnQsRes108, is108)
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

        // Quick Settings - Row 3: Top Shot
        binding.btnQsTopShotOff.setOnClickListener {
            topShotMode = 0
            updateQuickSettingsUi()
            Toast.makeText(this, "Top Shot Off", Toast.LENGTH_SHORT).show()
        }
        binding.btnQsTopShotAuto.setOnClickListener {
            topShotMode = 1
            updateQuickSettingsUi()
            Toast.makeText(this, "Top Shot Auto", Toast.LENGTH_SHORT).show()
        }
        binding.btnQsTopShotOn.setOnClickListener {
            topShotMode = 2
            updateQuickSettingsUi()
            Toast.makeText(this, "Top Shot On", Toast.LENGTH_SHORT).show()
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

        // Quick Settings - Row 5: Resolution
        binding.btnQsRes12.setOnClickListener { setQuickResolution(false) }
        binding.btnQsRes108.setOnClickListener { setQuickResolution(true) }

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

        // Tier 1: Shutter Button
        binding.btnShutter.setOnClickListener {
            onShutterClicked()
        }

        // Tier 1: Camera Switch (Front <-> Rear)
        binding.btnSwitchCamera.setOnClickListener {
            binding.btnSwitchCamera.animate().rotationBy(180f).setDuration(300).start()
            cameraManager.switchCamera()
            val isFront = cameraManager.isFrontCamera()
            binding.zoomPillContainer.visibility = if (isFront) View.GONE else View.VISIBLE
            if (!isFront) {
                selectZoomPill(binding.btnZoom1x)
            }
        }

        // Physical Zoom Selectors (.5 Ultrawide, 1x Main, 2x Tele, 5x Digital)
        binding.btnZoomUltrawide.setOnClickListener {
            cameraManager.switchToLens(CameraManager.CAMERA_ULTRAWIDE, 1.0f)
            selectZoomPill(binding.btnZoomUltrawide)
            binding.tvSensorInfo.text = "8MP UW"
        }

        binding.btnZoom1x.setOnClickListener {
            cameraManager.switchToLens(CameraManager.CAMERA_MAIN, 1.0f)
            selectZoomPill(binding.btnZoom1x)
            binding.tvSensorInfo.text = if (cameraManager.is108MpEnabled) "108MP ULTRA" else "108MP HM2"
        }

        binding.btnZoom2x.setOnClickListener {
            cameraManager.switchToLens(CameraManager.CAMERA_MAIN, 2.0f)
            selectZoomPill(binding.btnZoom2x)
            binding.tvSensorInfo.text = "2X SUPER RES"
        }

        binding.btnZoom5x.setOnClickListener {
            cameraManager.switchToLens(CameraManager.CAMERA_MAIN, 5.0f)
            selectZoomPill(binding.btnZoom5x)
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

        // Pro Controls: ISO Slider (0 = Auto, 100..6400)
        binding.sbIso.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (progress == 0) {
                    binding.tvIsoLabel.text = "ISO AUTO"
                    cameraManager.setManualIso(0)
                } else {
                    val iso = progress * 100
                    binding.tvIsoLabel.text = "ISO $iso"
                    cameraManager.setManualIso(iso)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Pro Controls: EV Slider
        binding.sbEv.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val ev = (progress - 12) * 0.166f
                binding.tvEvLabel.text = String.format("EV %+.1f", ev)
                cameraManager.setExposureCompensation(progress - 12)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Tap to Focus with Animated Pixel Reticle
        binding.viewFinder.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                if (binding.quickSettingsContainer.visibility == View.VISIBLE) {
                    toggleQuickSettings()
                    return@setOnTouchListener true
                }
                val factory = binding.viewFinder.meteringPointFactory
                val point = factory.createPoint(event.x, event.y)
                cameraManager.focusAt(point)
                showFocusRing(event.x, event.y)
            }
            true
        }

        // Gallery Thumbnail Click
        binding.ivGalleryThumbnail.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                type = "image/*"
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
        val zoomButtons = listOf(binding.btnZoomUltrawide, binding.btnZoom1x, binding.btnZoom2x, binding.btnZoom5x)
        for (btn in zoomButtons) {
            if (btn == selectedView) {
                btn.setBackgroundResource(R.drawable.bg_zoom_pill_active)
                btn.setTextColor(ContextCompat.getColor(this, R.color.pixel_accent_sand_text))
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

    private fun showFocusRing(x: Float, y: Float) {
        val container = binding.focusReticleContainer
        val halfW = container.width / 2
        val halfH = container.height / 2

        container.x = x - halfW
        container.y = y - halfH
        container.visibility = View.VISIBLE
        container.alpha = 1f
        container.scaleX = 1.3f
        container.scaleY = 1.3f

        container.animate()
            .scaleX(1.0f)
            .scaleY(1.0f)
            .setDuration(220)
            .withEndAction {
                container.animate()
                    .alpha(0f)
                    .setStartDelay(1200)
                    .setDuration(400)
                    .withEndAction { container.visibility = View.GONE }
                    .start()
            }
            .start()
    }

    private fun onShutterClicked() {
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

        lifecycleScope.launch {
            val blurStrength = 2f + (portraitBlurProgress / 100f) * 24f
            val uri: Uri? = cameraManager.capturePhoto(currentMode, blurStrength) { status ->
                runOnUiThread {
                    binding.tvProcessingStatus.text = status
                }
            }

            binding.processingPill.visibility = View.GONE
            binding.overlayNightHoldStill.visibility = View.GONE
            isCapturing = false

            if (uri != null) {
                loadLatestGalleryThumbnail()
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
        stopVideoRecordingUi()
    }

    companion object {
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
    }
}
