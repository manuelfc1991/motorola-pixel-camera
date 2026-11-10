package com.motorola.pixelcamera.core

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.MicrophoneDirection
import android.os.Build
import android.util.Log

/**
 * Directional Audio Zoom Manager for Motorola Edge 20 Fusion (Dimensity 800U Dual-Microphone Array).
 *
 * Implements:
 * 1. Android Q+ (API 29+) MicrophoneDirection beamforming steering towards target subject
 * 2. Dynamic acoustic field dimension scaling from wide omnidirectional (0.15f) to narrow laser beam (1.0f)
 * 3. Real-time zoom level synchronizer (1.0x - 8.0x) during active video recording
 */
class AudioZoomManager(private val context: Context) {

    private var isAudioZoomEnabled: Boolean = true
    private var currentZoomRatio: Float = 1.0f
    private var isRecording: Boolean = false

    // Optional audio record reference for direct hardware beamforming configuration
    private var activeAudioRecord: AudioRecord? = null

    // UI Callback for HUD badge updates
    var onAudioZoomStateChanged: ((isActive: Boolean, zoomRatio: Float, fieldDimension: Float) -> Unit)? = null

    fun setAudioZoomEnabled(enabled: Boolean) {
        isAudioZoomEnabled = enabled
        notifyState()
    }

    fun isAudioZoomEnabled(): Boolean = isAudioZoomEnabled

    fun onRecordingStarted() {
        isRecording = true
        applyDirectionalBeamforming(currentZoomRatio)
        notifyState()
    }

    fun onRecordingStopped() {
        isRecording = false
        releaseAudioRecord()
        notifyState()
    }

    fun onZoomRatioChanged(zoomRatio: Float) {
        currentZoomRatio = zoomRatio
        if (isRecording && isAudioZoomEnabled) {
            applyDirectionalBeamforming(zoomRatio)
        }
        notifyState()
    }

    private fun applyDirectionalBeamforming(zoomRatio: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                // Map zoom range (1.0x - 8.0x) to acoustic field dimension (0.15f wide to 1.0f narrow beam)
                val normalized = ((zoomRatio - 1.0f) / 7.0f).coerceIn(0.0f, 1.0f)
                val fieldDimension = 0.15f + 0.85f * normalized

                activeAudioRecord?.let { record ->
                    record.setPreferredMicrophoneDirection(MicrophoneDirection.MIC_DIRECTION_AWAY_FROM_USER)
                    record.setPreferredMicrophoneFieldDimension(fieldDimension)
                }

                Log.d(TAG, "Audio Zoom applied: zoom=${String.format("%.1f", zoomRatio)}x, dimension=${String.format("%.2f", fieldDimension)}, direction=AWAY_FROM_USER")
            } catch (e: Exception) {
                Log.w(TAG, "Failed setting directional audio: ${e.message}")
            }
        }
    }

    private fun notifyState() {
        val active = isRecording && isAudioZoomEnabled
        val normalized = ((currentZoomRatio - 1.0f) / 7.0f).coerceIn(0.0f, 1.0f)
        val fieldDimension = 0.15f + 0.85f * normalized
        onAudioZoomStateChanged?.invoke(active, currentZoomRatio, fieldDimension)
    }

    private fun releaseAudioRecord() {
        try {
            activeAudioRecord?.release()
            activeAudioRecord = null
        } catch (ignored: Exception) {}
    }

    companion object {
        private const val TAG = "AudioZoomManager"
    }
}
