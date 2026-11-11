package com.motorola.pixelcamera.core

import com.motorola.pixelcamera.core.VideoExposureAssist.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoExposureAssistTest {

    private val target = VideoExposureAssist.TARGET_LUMA

    private fun stuck(assist: VideoExposureAssist, iso: Int = 1789): Int? {
        var taken: Int? = null
        repeat(15) { taken = assist.onAutoResult(underexposed = true, iso = iso) }
        return taken
    }

    /** A scene whose preview brightness follows ISO; [isoForTarget] gives a normal picture. */
    private fun luma(iso: Int, isoForTarget: Int) =
        (target * Math.pow(iso.toDouble() / isoForTarget, 1 / 1.3)).toFloat().coerceAtMost(255f)

    @Test
    fun staysOutWhileTheDriverCopes() {
        val assist = VideoExposureAssist()
        repeat(100) { assertNull(assist.onAutoResult(underexposed = false, iso = 400)) }
        assertFalse(assist.active)
    }

    @Test
    fun aShortDipDoesNotTrigger() {
        val assist = VideoExposureAssist()
        repeat(5) {
            repeat(10) { assertNull(assist.onAutoResult(true, 1789)) }
            assertNull(assist.onAutoResult(false, 1789))
        }
        assertFalse(assist.active)
    }

    @Test
    fun staysOutWhenTheDriverIsAlreadyAtItsLimit() {
        // 1080p: dark, but the driver has used all the ISO there is.
        val assist = VideoExposureAssist()
        repeat(100) { assertNull(assist.onAutoResult(true, 12796)) }
        assertFalse(assist.active)
    }

    @Test
    fun takesOverAfterHalfASecondStuckDark() {
        val assist = VideoExposureAssist()
        assertEquals(1789, stuck(assist))
        assertTrue(assist.active)
    }

    @Test
    fun climbsSmoothlyToTheRightBrightnessAndSettles() {
        val assist = VideoExposureAssist()
        stuck(assist)
        var iso = assist.iso
        var steps = 0
        while (true) {
            val step = assist.onBrightness(luma(iso, 6000), target)
            if (step !is Step.Iso) break
            assertTrue("no jump larger than a quarter: $iso -> ${step.iso}", step.iso <= iso * 1.26)
            iso = step.iso
            assertTrue(++steps < 30)
        }
        assertTrue("settled near the needed ISO: $iso", iso in 4500..7500)
        // Settled: further samples change nothing.
        repeat(5) { assertEquals(Step.Hold, assist.onBrightness(luma(iso, 6000), target)) }
    }

    @Test
    fun neverGoesPastTheCeiling() {
        val assist = VideoExposureAssist()
        stuck(assist)
        repeat(50) { assist.onBrightness(5f, target) }
        assertEquals(VideoExposureAssist.MAX_ISO, assist.iso)
        assertEquals(Step.Hold, assist.onBrightness(5f, target))
    }

    @Test
    fun handsBackWhenTheSceneGetsBright() {
        val assist = VideoExposureAssist()
        stuck(assist)
        repeat(50) { assist.onBrightness(5f, target) }
        var handedBack = false
        repeat(60) { if (assist.onBrightness(240f, target) == Step.HandBack) handedBack = true }
        assertTrue(handedBack)
        assertFalse(assist.active)
        // And it can take over again later.
        assertEquals(1789, stuck(assist))
    }

    @Test
    fun reportsWhenTheLimitIsNotEnough() {
        val assist = VideoExposureAssist().apply { maxIso = 2800 }
        assertEquals(1789, stuck(assist))
        assertFalse(assist.stillDark)
        repeat(10) { assist.onBrightness(20f, target) }
        assertEquals(2800, assist.iso)
        assertTrue(assist.stillDark)
        // Bright enough at the limit: no complaint.
        assist.onBrightness(target, target)
        assertFalse(assist.stillDark)
    }

    @Test
    fun resetReturnsControlToTheDriver() {
        val assist = VideoExposureAssist()
        stuck(assist)
        assist.reset()
        assertFalse(assist.active)
        assertEquals(Step.Hold, assist.onBrightness(5f, target))
    }
}
