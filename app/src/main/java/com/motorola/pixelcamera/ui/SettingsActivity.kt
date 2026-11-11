package com.motorola.pixelcamera.ui

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import com.motorola.pixelcamera.computational.HdrProcessor
import com.motorola.pixelcamera.databinding.ActivitySettingsBinding
import java.util.Locale

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        loadPreferences()
        setupListeners()
    }

    private fun loadPreferences() {
        binding.swLocation.isChecked = prefs.getBoolean(KEY_SAVE_LOCATION, true)
        binding.swSounds.isChecked = prefs.getBoolean(KEY_CAMERA_SOUNDS, true)
        binding.swEis.isChecked = prefs.getBoolean(KEY_EIS_ENABLED, true)
        binding.swHighBitrate.isChecked = prefs.getBoolean(KEY_HIGH_BITRATE, false)
        binding.swRememberMode.isChecked = prefs.getBoolean(KEY_REMEMBER_MODE, false)
        binding.swMultiFrameLowLight.isChecked = prefs.getBoolean(KEY_MULTI_FRAME_LOW_LIGHT, true)
        binding.swMultiFrameZoom.isChecked = prefs.getBoolean(KEY_MULTI_FRAME_ZOOM, true)

        val sat = prefs.getInt(KEY_SATURATION, 100)
        binding.sbSaturation.progress = sat
        binding.tvSaturationLabel.text = String.format(Locale.US, "Color Saturation: %.2fx", sat / 100f)

        val contrast = prefs.getInt(KEY_MICRO_CONTRAST, 100)
        binding.sbMicroContrast.progress = contrast
        binding.tvMicroContrastLabel.text = String.format(Locale.US, "Micro-Contrast: %.2fx", contrast / 100f)

        val sharp = prefs.getInt(KEY_SHARPNESS, 100)
        binding.sbSharpness.progress = sharp
        binding.tvSharpnessLabel.text = String.format(Locale.US, "Edge Sharpening: %.2fx", sharp / 100f)
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.swLocation.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_SAVE_LOCATION, isChecked).apply()
        }

        binding.swSounds.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_CAMERA_SOUNDS, isChecked).apply()
        }

        binding.swEis.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_EIS_ENABLED, isChecked).apply()
        }

        mapOf(
            binding.swHighBitrate to KEY_HIGH_BITRATE,
            binding.swRememberMode to KEY_REMEMBER_MODE,
            binding.swMultiFrameLowLight to KEY_MULTI_FRAME_LOW_LIGHT,
            binding.swMultiFrameZoom to KEY_MULTI_FRAME_ZOOM
        ).forEach { (switch, key) ->
            switch.setOnCheckedChangeListener { _, isChecked -> prefs.edit().putBoolean(key, isChecked).apply() }
        }

        binding.sbSaturation.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val mult = progress / 100f
                binding.tvSaturationLabel.text = String.format(Locale.US, "Color Saturation: %.2fx", mult)
                HdrProcessor.saturationMultiplier = mult
                prefs.edit().putInt(KEY_SATURATION, progress).apply()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.sbMicroContrast.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val mult = progress / 100f
                binding.tvMicroContrastLabel.text = String.format(Locale.US, "Micro-Contrast: %.2fx", mult)
                HdrProcessor.microContrastMultiplier = mult
                prefs.edit().putInt(KEY_MICRO_CONTRAST, progress).apply()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.sbSharpness.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val mult = progress / 100f
                binding.tvSharpnessLabel.text = String.format(Locale.US, "Edge Sharpening: %.2fx", mult)
                HdrProcessor.sharpnessMultiplier = mult
                prefs.edit().putInt(KEY_SHARPNESS, progress).apply()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    companion object {
        const val PREFS_NAME = "PixelCameraPrefs"
        const val KEY_SAVE_LOCATION = "save_location"
        const val KEY_CAMERA_SOUNDS = "camera_sounds"
        const val KEY_EIS_ENABLED = "eis_enabled"
        const val KEY_FLASH_MODE = "flash_mode"
        const val KEY_RAW_ENABLED = "raw_enabled"
        const val KEY_FRONT_FULL_RES = "front_full_res"
        const val KEY_HDR_MODE = "hdr_plus_mode"
        const val KEY_GRID_MODE = "grid_mode"
        const val KEY_VIDEO_QUALITY = "video_quality"
        const val KEY_VIDEO_FPS = "video_fps"
        const val KEY_LOW_LIGHT_VIDEO = "low_light_video"
        const val KEY_SATURATION = "pref_saturation"
        const val KEY_MICRO_CONTRAST = "pref_micro_contrast"
        const val KEY_SHARPNESS = "pref_sharpness"
        const val KEY_HIGH_BITRATE = "video_high_bitrate"
        const val KEY_REMEMBER_MODE = "remember_mode"
        const val KEY_LAST_MODE = "last_mode"
        const val KEY_MULTI_FRAME_LOW_LIGHT = "multi_frame_low_light"
        const val KEY_MULTI_FRAME_ZOOM = "multi_frame_zoom"
        const val KEY_MIRROR_FRONT = "mirror_front"
        const val KEY_MIRROR_BACK = "mirror_back"
        const val KEY_MOTION_PHOTO = "motion_photo"
    }
}
