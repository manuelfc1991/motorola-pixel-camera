package com.motorola.pixelcamera.ui

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import com.motorola.pixelcamera.databinding.ActivitySettingsBinding

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
    }
}
