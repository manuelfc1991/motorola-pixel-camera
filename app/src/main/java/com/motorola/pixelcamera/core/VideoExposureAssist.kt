package com.motorola.pixelcamera.core

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Keeps video from going dark when the driver's own exposure gives up early.
 *
 * At 4K and a fixed 30 frames a second the driver stops raising ISO at about 1800, well before
 * the stream has to slow down, and reports the picture as underexposed. Indoors in the evening
 * that leaves 4K video darker than it needs to be.
 *
 * This class decides only. While the driver copes, it stays out. Once the driver reports an
 * underexposed picture with ISO to spare, it takes over the ISO, steers it from the brightness of
 * the preview picture, and hands control back when the scene no longer needs it.
 */
class VideoExposureAssist {

    /** Highest ISO this class will set. Lower it where more would cost frame rate. */
    var maxIso: Int = MAX_ISO
        @Synchronized set

    /** True when the ISO is at [maxIso] and the picture is still too dark. */
    var stillDark = false
        @Synchronized get
        private set


    sealed class Step {
        /** Leave things as they are. */
        object Hold : Step()
        /** Set this ISO. */
        data class Iso(val iso: Int) : Step()
        /** The scene is bright enough again: give exposure back to the driver. */
        object HandBack : Step()
    }

    private var boostIso = 0
    private var floorIso = 0
    private var stuckFrames = 0

    /** True while this class, not the driver, sets the ISO. */
    val active: Boolean @Synchronized get() = boostIso > 0

    /** The ISO to use while [active]. */
    val iso: Int @Synchronized get() = boostIso

    /**
     * Call for each frame the driver exposed by itself. [underexposed] is the driver's own verdict.
     * Returns the ISO to take over with, or null to leave the driver in charge.
     */
    @Synchronized
    fun onAutoResult(underexposed: Boolean, iso: Int): Int? {
        if (boostIso > 0) return null
        if (underexposed && iso < maxIso * HEADROOM) stuckFrames++ else stuckFrames = 0
        if (stuckFrames < STUCK_FRAMES) return null
        stuckFrames = 0
        floorIso = iso
        boostIso = iso
        return iso
    }

    /**
     * Call a few times a second while [active] with the preview's average brightness (0..255)
     * and the brightness to aim for.
     */
    @Synchronized
    fun onBrightness(luma: Float, target: Float): Step {
        if (boostIso == 0) return Step.Hold
        stillDark = boostIso >= maxIso && luma < target * BAND_LOW
        if (luma >= target * BAND_LOW && luma <= target * BAND_HIGH) return Step.Hold
        // Screen brightness rises more slowly than light, hence the exponent. Small steps, so a
        // recording brightens smoothly instead of jumping.
        val ratio = (target / luma.coerceAtLeast(1f)).pow(GAMMA).coerceIn(1f / MAX_STEP, MAX_STEP)
        var next = (boostIso * ratio).roundToInt().coerceAtMost(maxIso)
        if (next < floorIso) {
            if (boostIso == floorIso) {
                reset()
                return Step.HandBack
            }
            next = floorIso
        }
        if (next == boostIso) return Step.Hold
        boostIso = next
        return Step.Iso(next)
    }

    /** Back to the driver's exposure, for a new camera session. */
    @Synchronized
    fun reset() {
        boostIso = 0
        floorIso = 0
        stuckFrames = 0
        stillDark = false
    }

    companion object {
        /** What the driver itself reaches at 1080p; above it the picture is mostly noise. */
        const val MAX_ISO = 12800
        /** Brightness of a normally exposed preview. */
        const val TARGET_LUMA = 110f

        /** The driver counts as stuck only while its ISO is below this share of [MAX_ISO]. */
        private const val HEADROOM = 0.8f
        /** Half a second of underexposed frames at 30 a second. */
        private const val STUCK_FRAMES = 15
        private const val BAND_LOW = 0.82f
        private const val BAND_HIGH = 1.22f
        private const val GAMMA = 1.3f
        private const val MAX_STEP = 1.25f
    }
}
