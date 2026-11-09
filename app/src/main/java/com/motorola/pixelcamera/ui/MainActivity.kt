package com.motorola.pixelcamera.ui

import android.Manifest
import android.content.Intent
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.motorola.pixelcamera.R
import com.motorola.pixelcamera.core.CameraManager
import com.motorola.pixelcamera.core.CameraMode
import com.motorola.pixelcamera.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraManager: CameraManager

    private var currentMode: CameraMode = CameraMode.CAMERA
    private var isCapturing = false

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

        cameraManager = CameraManager(this)

        setupListeners()

        if (allPermissionsGranted()) {
            setupCamera()
        } else {
            permissionLauncher.launch(REQUIRED_PERMISSIONS)
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
        // Shutter Button
        binding.btnShutter.setOnClickListener {
            onShutterClicked()
        }

        // Camera Switch
        binding.btnSwitchCamera.setOnClickListener {
            binding.btnSwitchCamera.animate().rotationBy(180f).setDuration(300).start()
            cameraManager.switchCamera(this, binding.viewFinder)
        }

        // Zoom Selectors (.6x, 1x, 2x)
        binding.btnZoomUltrawide.setOnClickListener { updateZoom(0.6f, binding.btnZoomUltrawide) }
        binding.btnZoom1x.setOnClickListener { updateZoom(1.0f, binding.btnZoom1x) }
        binding.btnZoom2x.setOnClickListener { updateZoom(2.0f, binding.btnZoom2x) }

        // Mode Carousel
        binding.modeNightSight.setOnClickListener { setMode(CameraMode.NIGHT_SIGHT, binding.modeNightSight) }
        binding.modePortrait.setOnClickListener { setMode(CameraMode.PORTRAIT, binding.modePortrait) }
        binding.modeCamera.setOnClickListener { setMode(CameraMode.CAMERA, binding.modeCamera) }
        binding.modeVideo.setOnClickListener { setMode(CameraMode.VIDEO, binding.modeVideo) }
        binding.modePro.setOnClickListener { setMode(CameraMode.PRO, binding.modePro) }

        // RAW Badge Toggle
        binding.tvRawBadge.setOnClickListener {
            cameraManager.isRawEnabled = !cameraManager.isRawEnabled
            if (cameraManager.isRawEnabled) {
                binding.tvRawBadge.setTextColor(ContextCompat.getColor(this, R.color.pixel_accent_yellow))
                Toast.makeText(this, "RAW (DNG) capture enabled", Toast.LENGTH_SHORT).show()
            } else {
                binding.tvRawBadge.setTextColor(ContextCompat.getColor(this, R.color.pixel_text_secondary))
                Toast.makeText(this, "RAW capture disabled", Toast.LENGTH_SHORT).show()
            }
        }

        // 108 MP Badge Toggle
        binding.tv108MpBadge.setOnClickListener {
            cameraManager.is108MpEnabled = !cameraManager.is108MpEnabled
            if (cameraManager.is108MpEnabled) {
                binding.tv108MpBadge.setTextColor(ContextCompat.getColor(this, R.color.pixel_accent_blue))
                binding.tvSensorInfo.text = "108MP ULTRA"
                Toast.makeText(this, "108MP High-Resolution Mode ON", Toast.LENGTH_SHORT).show()
            } else {
                binding.tv108MpBadge.setTextColor(ContextCompat.getColor(this, R.color.pixel_text_secondary))
                binding.tvSensorInfo.text = "108MP HM2"
                Toast.makeText(this, "108MP Nona-binning (12MP) Mode", Toast.LENGTH_SHORT).show()
            }
        }

        // Pro Controls: ISO Slider
        binding.sbIso.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (progress == 0) {
                    binding.tvIsoLabel.text = "ISO AUTO"
                } else {
                    val iso = progress * 100
                    binding.tvIsoLabel.text = "ISO $iso"
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

    private fun updateZoom(zoom: Float, selectedView: TextView) {
        cameraManager.setZoom(zoom)
        val zoomButtons = listOf(binding.btnZoomUltrawide, binding.btnZoom1x, binding.btnZoom2x)
        for (btn in zoomButtons) {
            if (btn == selectedView) {
                btn.setBackgroundResource(R.drawable.bg_pill_container)
                btn.backgroundTintList = ContextCompat.getColorStateList(this, R.color.pixel_pill_active)
                btn.setTextColor(ContextCompat.getColor(this, R.color.pixel_pill_text_active))
            } else {
                btn.background = null
                btn.setTextColor(ContextCompat.getColor(this, R.color.pixel_text_secondary))
            }
        }
    }

    private fun setMode(mode: CameraMode, selectedView: TextView) {
        currentMode = mode
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

        // Update Top Badges & Pro Drawer Visibility
        when (mode) {
            CameraMode.NIGHT_SIGHT -> {
                binding.tvHdrBadge.text = "NIGHT SIGHT"
                binding.proControlPanel.visibility = View.GONE
                binding.histogramView.visibility = View.GONE
            }
            CameraMode.PORTRAIT -> {
                binding.tvHdrBadge.text = "PORTRAIT DEPTH"
                binding.proControlPanel.visibility = View.GONE
                binding.histogramView.visibility = View.GONE
            }
            CameraMode.PRO -> {
                binding.tvHdrBadge.text = "PRO MANUAL"
                binding.proControlPanel.visibility = View.VISIBLE
                binding.histogramView.visibility = View.VISIBLE
                // Trigger simulated live histogram
                val r = IntArray(256) { (Math.sin(it * 0.04) * 50 + 60).toInt() }
                val g = IntArray(256) { (Math.sin(it * 0.05 + 1.0) * 60 + 70).toInt() }
                val b = IntArray(256) { (Math.sin(it * 0.03 + 2.0) * 45 + 50).toInt() }
                binding.histogramView.updateHistogram(r, g, b)
            }
            CameraMode.VIDEO -> {
                binding.tvHdrBadge.text = "EIS 4K"
                binding.proControlPanel.visibility = View.GONE
                binding.histogramView.visibility = View.GONE
            }
            CameraMode.CAMERA -> {
                binding.tvHdrBadge.text = "HDR+ ON"
                binding.proControlPanel.visibility = View.GONE
                binding.histogramView.visibility = View.GONE
            }
        }
    }

    private fun showFocusRing(x: Float, y: Float) {
        val ring = binding.focusRing
        val halfW = ring.width / 2
        val halfH = ring.height / 2

        ring.x = x - halfW
        ring.y = y - halfH
        ring.visibility = View.VISIBLE
        ring.alpha = 1f
        ring.scaleX = 1.3f
        ring.scaleY = 1.3f

        ring.animate()
            .scaleX(1.0f)
            .scaleY(1.0f)
            .setDuration(220)
            .withEndAction {
                ring.animate()
                    .alpha(0f)
                    .setStartDelay(1000)
                    .setDuration(400)
                    .withEndAction { ring.visibility = View.GONE }
                    .start()
            }
            .start()
    }

    private fun onShutterClicked() {
        if (isCapturing) return
        isCapturing = true

        binding.btnShutter.animate().scaleX(0.88f).scaleY(0.88f).setDuration(100).withEndAction {
            binding.btnShutter.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
        }.start()

        binding.processingPill.visibility = View.VISIBLE

        lifecycleScope.launch {
            val uri: Uri? = cameraManager.capturePhoto(currentMode) { status ->
                runOnUiThread {
                    binding.tvProcessingStatus.text = status
                }
            }

            binding.processingPill.visibility = View.GONE
            isCapturing = false

            if (uri != null) {
                binding.ivGalleryThumbnail.setImageURI(uri)
                Toast.makeText(this@MainActivity, "Photo saved to Gallery", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@MainActivity, "Capture failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    companion object {
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
    }
}
