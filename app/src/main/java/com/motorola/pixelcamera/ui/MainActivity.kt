package com.motorola.pixelcamera.ui

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
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

        if (allPermissionsGranted()) {
            setupCamera()
        } else {
            permissionLauncher.launch(REQUIRED_PERMISSIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        syncSettings()
    }

    private fun syncSettings() {
        val rawPref = prefs.getBoolean(SettingsActivity.KEY_RAW_ENABLED, false)
        cameraManager.isRawEnabled = rawPref
        if (rawPref) {
            binding.tvRawBadge.setTextColor(ContextCompat.getColor(this, R.color.pixel_accent_yellow))
        } else {
            binding.tvRawBadge.setTextColor(ContextCompat.getColor(this, R.color.pixel_text_secondary))
        }

        val remosaicPref = prefs.getBoolean(SettingsActivity.KEY_108MP_REMOSAIC, true)
        cameraManager.is108MpEnabled = remosaicPref
        if (remosaicPref) {
            binding.tv108MpBadge.setTextColor(ContextCompat.getColor(this, R.color.pixel_accent_blue))
            binding.tvSensorInfo.text = "108MP ULTRA"
        } else {
            binding.tv108MpBadge.setTextColor(ContextCompat.getColor(this, R.color.pixel_text_secondary))
            binding.tvSensorInfo.text = "108MP HM2"
        }

        val flashPref = prefs.getInt(SettingsActivity.KEY_FLASH_MODE, ImageCapture.FLASH_MODE_OFF)
        cameraManager.flashMode = flashPref
        updateFlashUi(flashPref)
        updateQuickSettingsUi()
    }

    private fun updateQuickSettingsUi() {
        val fMode = cameraManager.flashMode
        binding.qsFlashOff.setTextColor(ContextCompat.getColor(this, if (fMode == ImageCapture.FLASH_MODE_OFF) R.color.pixel_pill_active else R.color.pixel_text_secondary))
        binding.qsFlashAuto.setTextColor(ContextCompat.getColor(this, if (fMode == ImageCapture.FLASH_MODE_AUTO) R.color.pixel_accent_yellow else R.color.pixel_text_secondary))
        binding.qsFlashOn.setTextColor(ContextCompat.getColor(this, if (fMode == ImageCapture.FLASH_MODE_ON) R.color.pixel_accent_yellow else R.color.pixel_text_secondary))

        val isRaw = cameraManager.isRawEnabled
        binding.qsRawJpeg.setTextColor(ContextCompat.getColor(this, if (!isRaw) R.color.pixel_pill_active else R.color.pixel_text_secondary))
        binding.qsRawDng.setTextColor(ContextCompat.getColor(this, if (isRaw) R.color.pixel_accent_yellow else R.color.pixel_text_secondary))

        val is108 = cameraManager.is108MpEnabled
        binding.qsRes12.setTextColor(ContextCompat.getColor(this, if (!is108) R.color.pixel_pill_active else R.color.pixel_text_secondary))
        binding.qsRes108.setTextColor(ContextCompat.getColor(this, if (is108) R.color.pixel_accent_blue else R.color.pixel_text_secondary))
    }

    private fun updateFlashUi(mode: Int) {
        when (mode) {
            ImageCapture.FLASH_MODE_AUTO -> {
                binding.btnFlash.setImageResource(R.drawable.ic_flash_auto)
                ImageViewCompat.setImageTintList(binding.btnFlash, ContextCompat.getColorStateList(this, R.color.pixel_accent_yellow))
            }
            ImageCapture.FLASH_MODE_ON -> {
                binding.btnFlash.setImageResource(R.drawable.ic_flash_on)
                ImageViewCompat.setImageTintList(binding.btnFlash, ContextCompat.getColorStateList(this, R.color.pixel_accent_yellow))
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

    private fun setupListeners() {
        // Settings / Quick Settings Toggle
        binding.btnSettings.setOnClickListener {
            if (binding.quickSettingsSheet.visibility == View.VISIBLE) {
                binding.quickSettingsSheet.visibility = View.GONE
            } else {
                updateQuickSettingsUi()
                binding.quickSettingsSheet.visibility = View.VISIBLE
            }
        }

        binding.btnQuickSettingsDone.setOnClickListener {
            binding.quickSettingsSheet.visibility = View.GONE
        }

        binding.btnOpenFullSettings.setOnClickListener {
            binding.quickSettingsSheet.visibility = View.GONE
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Quick Settings Toggles
        binding.qsFlashOff.setOnClickListener { setQuickFlash(ImageCapture.FLASH_MODE_OFF) }
        binding.qsFlashAuto.setOnClickListener { setQuickFlash(ImageCapture.FLASH_MODE_AUTO) }
        binding.qsFlashOn.setOnClickListener { setQuickFlash(ImageCapture.FLASH_MODE_ON) }

        binding.qsRawJpeg.setOnClickListener { setQuickRaw(false) }
        binding.qsRawDng.setOnClickListener { setQuickRaw(true) }

        binding.qsRes12.setOnClickListener { setQuickResolution(false) }
        binding.qsRes108.setOnClickListener { setQuickResolution(true) }

        // Flash Button Toggle (Off -> Auto -> On -> Off)
        binding.btnFlash.setOnClickListener {
            val nextFlash = when (cameraManager.flashMode) {
                ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_AUTO
                ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
                else -> ImageCapture.FLASH_MODE_OFF
            }
            setQuickFlash(nextFlash)
            val statusText = when (nextFlash) {
                ImageCapture.FLASH_MODE_AUTO -> "Flash Auto"
                ImageCapture.FLASH_MODE_ON -> "Flash On"
                else -> "Flash Off"
            }
            Toast.makeText(this, statusText, Toast.LENGTH_SHORT).show()
        }

        // Shutter Button
        binding.btnShutter.setOnClickListener {
            onShutterClicked()
        }

        // Camera Switch (Front <-> Rear)
        binding.btnSwitchCamera.setOnClickListener {
            binding.btnSwitchCamera.animate().rotationBy(180f).setDuration(300).start()
            cameraManager.switchCamera()
            val isFront = cameraManager.isFrontCamera()
            binding.zoomPillContainer.visibility = if (isFront) View.GONE else View.VISIBLE
            if (!isFront) {
                selectZoomPill(binding.btnZoom1x)
            }
        }

        // Physical Zoom Selectors (.6x Ultrawide Camera 3, 1x Main Camera 0, 2x Tele Camera 0)
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

        // Mode Carousel
        binding.modeNightSight.setOnClickListener { setMode(CameraMode.NIGHT_SIGHT, binding.modeNightSight) }
        binding.modePortrait.setOnClickListener { setMode(CameraMode.PORTRAIT, binding.modePortrait) }
        binding.modeCamera.setOnClickListener { setMode(CameraMode.CAMERA, binding.modeCamera) }
        binding.modeVideo.setOnClickListener { setMode(CameraMode.VIDEO, binding.modeVideo) }
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
        val zoomButtons = listOf(binding.btnZoomUltrawide, binding.btnZoom1x, binding.btnZoom2x)
        for (btn in zoomButtons) {
            if (btn == selectedView) {
                btn.setBackgroundResource(R.drawable.bg_zoom_pill_active)
                btn.setTextColor(ContextCompat.getColor(this, R.color.pixel_pill_text_active))
            } else {
                btn.background = null
                btn.setTextColor(ContextCompat.getColor(this, R.color.pixel_text_secondary))
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

        val modeViews = listOf(
            binding.modeNightSight,
            binding.modePortrait,
            binding.modeCamera,
            binding.modeVideo,
            binding.modePro
        )

        for (v in modeViews) {
            if (v == selectedView) {
                v.setBackgroundResource(R.drawable.bg_pill_container)
                v.backgroundTintList = ContextCompat.getColorStateList(this, R.color.pixel_pill_active)
                v.setTextColor(ContextCompat.getColor(this, R.color.pixel_pill_text_active))
                v.paint.isFakeBoldText = true
            } else {
                v.background = null
                v.setTextColor(ContextCompat.getColor(this, R.color.pixel_text_secondary))
                v.paint.isFakeBoldText = false
            }
        }

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

        // Rebind use cases for optimal performance in active mode (e.g. VideoCapture vs ImageAnalysis)
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
                binding.ivGalleryThumbnail.setImageURI(uri)
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
                                val uri = event.outputResults.outputUri
                                binding.ivGalleryThumbnail.setImageURI(uri)
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
