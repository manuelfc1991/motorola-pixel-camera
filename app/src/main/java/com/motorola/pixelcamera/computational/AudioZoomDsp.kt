package com.motorola.pixelcamera.computational

import kotlin.math.abs
import kotlin.math.pow

/**
 * Audio zoom for a two-microphone phone: as the picture zooms in, the sound narrows to what is in
 * front and comes closer.
 *
 * Stereo is split into mid (what both microphones hear alike: the subject ahead) and side (what
 * differs between them: sound from the left and right). Zooming turns the side part down and the
 * whole up, then a soft limiter keeps loud sounds from clipping. Changes are ramped across each
 * buffer so a zoom never clicks.
 */
class AudioZoomDsp {

    private var width = 1f
    private var gain = 1f

    /** How far the zoom effect has gone, 0 at 1x to 1 at [FULL_EFFECT_ZOOM] and beyond. */
    fun amountFor(zoom: Float): Float = ((zoom - 1f) / (FULL_EFFECT_ZOOM - 1f)).coerceIn(0f, 1f)

    /** Stereo width kept at [zoom]: 1 = as recorded, 0 = all from the front. */
    fun widthFor(zoom: Float): Float = 1f - MAX_NARROWING * amountFor(zoom)

    /** Loudness boost at [zoom]: up to [MAX_BOOST_DB]. */
    fun gainFor(zoom: Float): Float = 10f.pow(MAX_BOOST_DB * amountFor(zoom) / 20f)

    /** Processes [frames] frames of 16-bit PCM in place ([channels] 1 or 2, interleaved). */
    fun process(pcm: ShortArray, frames: Int, channels: Int, zoom: Float) {
        if (frames <= 0) return
        val targetWidth = widthFor(zoom)
        val targetGain = gainFor(zoom)
        val w0 = width; val g0 = gain
        for (f in 0 until frames) {
            val t = (f + 1).toFloat() / frames
            val w = w0 + (targetWidth - w0) * t
            val g = g0 + (targetGain - g0) * t
            if (channels == 2) {
                val l = pcm[2 * f] / 32768f
                val r = pcm[2 * f + 1] / 32768f
                val mid = (l + r) * 0.5f
                val side = (l - r) * 0.5f * w
                pcm[2 * f] = toPcm(limit((mid + side) * g))
                pcm[2 * f + 1] = toPcm(limit((mid - side) * g))
            } else {
                pcm[f] = toPcm(limit(pcm[f] / 32768f * g))
            }
        }
        width = targetWidth
        gain = targetGain
    }

    private fun toPcm(x: Float): Short = (x * 32767f).toInt().coerceIn(-32768, 32767).toShort()

    companion object {
        const val FULL_EFFECT_ZOOM = 5f
        const val MAX_NARROWING = 0.85f
        const val MAX_BOOST_DB = 6f
        private const val KNEE = 0.7f

        /** Unchanged below the knee, then bends smoothly towards full scale without ever reaching it. */
        fun limit(x: Float): Float {
            val a = abs(x)
            if (a <= KNEE) return x
            val over = a - KNEE
            val room = 1f - KNEE
            val bent = KNEE + room * over / (over + room)
            return if (x < 0) -bent else bent
        }
    }
}
