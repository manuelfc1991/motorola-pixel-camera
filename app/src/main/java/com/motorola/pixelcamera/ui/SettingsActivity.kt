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
        binding.swRaw.isChecked = prefs.getBoolean(KEY_RAW_ENABLED, false)
        binding.swUltraHdr.isChecked = prefs.getBoolean(KEY_ULTRA_HDR, true)
        binding.sw108MpRemosaic.isChecked = prefs.getBoolean(KEY_108MP_REMOSAIC, true)
        binding.swEis.isChecked = prefs.getBoolean(KEY_EIS_ENABLED, true)
        binding.swHevc.isChecked = prefs.getBoolean(KEY_HEVC_ENABLED, true)

        val hdrFrames = prefs.getInt(KEY_HDR_FRAMES, 18)
        binding.sbHdrFrames.progress = hdrFrames
        binding.tvHdrFramesLabel.text = "HDR+ Frame Count: $hdrFrames frames"

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

        binding.swRaw.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_RAW_ENABLED, isChecked).apply()
        }

        binding.swUltraHdr.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_ULTRA_HDR, isChecked).apply()
        }

        binding.sw108MpRemosaic.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_108MP_REMOSAIC, isChecked).apply()
        }

        binding.swEis.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_EIS_ENABLED, isChecked).apply()
        }

        binding.swHevc.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_HEVC_ENABLED, isChecked).apply()
        }

        binding.sbHdrFrames.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.tvHdrFramesLabel.text = "HDR+ Frame Count: $progress frames"
                prefs.edit().putInt(KEY_HDR_FRAMES, progress).apply()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

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
        const val KEY_RAW_ENABLED = "raw_enabled"
        const val KEY_ULTRA_HDR = "ultra_hdr"
        const val KEY_108MP_REMOSAIC = "remosaic_108mp"
        const val KEY_EIS_ENABLED = "eis_enabled"
        const val KEY_HEVC_ENABLED = "hevc_enabled"
        const val KEY_HDR_FRAMES = "hdr_frames"
        const val KEY_FLASH_MODE = "flash_mode"
        const val KEY_VIDEO_QUALITY = "video_quality"
        const val KEY_VIDEO_FPS = "video_fps"
        const val KEY_SATURATION = "pref_saturation"
        const val KEY_MICRO_CONTRAST = "pref_micro_contrast"
        const val KEY_SHARPNESS = "pref_sharpness"
    }
}
