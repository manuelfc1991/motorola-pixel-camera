package com.motorola.pixelcamera.computational

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

class AudioZoomDspTest {

    /** A tone on the left only (side-heavy) plus a tone in both (the subject ahead). */
    private fun scene(frames: Int) = ShortArray(frames * 2).also { pcm ->
        for (f in 0 until frames) {
            val ahead = 4000 * sin(f * 0.05)
            val left = 4000 * sin(f * 0.13)
            pcm[2 * f] = (ahead + left).toInt().toShort()
            pcm[2 * f + 1] = ahead.toInt().toShort()
        }
    }

    private fun sideEnergy(pcm: ShortArray, from: Int, to: Int): Double {
        var e = 0.0
        for (f in from until to) { val s = (pcm[2 * f] - pcm[2 * f + 1]) / 2.0; e += s * s }
        return e
    }

    @Test
    fun noZoomLeavesTheSoundAlone() {
        val dsp = AudioZoomDsp()
        val original = scene(2000)
        val pcm = original.copyOf()
        dsp.process(pcm, 2000, 2, 1f)
        for (i in pcm.indices) assertTrue(abs(pcm[i] - original[i]) <= 1)
    }

    @Test
    fun zoomingNarrowsAndBoosts() {
        val dsp = AudioZoomDsp()
        dsp.process(scene(4000), 4000, 2, 5f)   // the change ramps in over this first buffer
        val original = scene(4000)
        val zoomed = original.copyOf()
        dsp.process(zoomed, 4000, 2, 5f)
        // Side kept at 15 % width and boosted 2x: about (0.15 * 2)^2 = 9 % of its energy.
        assertEquals(0.09, sideEnergy(zoomed, 0, 4000) / sideEnergy(original, 0, 4000), 0.02)
        // The sound from the front comes up by about 6 dB.
        val f = 31   // a frame where the front tone is strong
        val midIn = (original[2 * f] + original[2 * f + 1]) / 2.0
        val midOut = (zoomed[2 * f] + zoomed[2 * f + 1]) / 2.0
        assertEquals(2.0, midOut / midIn, 0.05)
    }

    @Test
    fun loudSoundsDoNotClip() {
        assertEquals(0.5f, AudioZoomDsp.limit(0.5f), 0f)
        assertTrue(AudioZoomDsp.limit(3f) < 1f)
        assertTrue(AudioZoomDsp.limit(-3f) > -1f)
        assertTrue(AudioZoomDsp.limit(0.9f) > AudioZoomDsp.limit(0.8f))
    }

    @Test
    fun effectGrowsWithZoom() {
        val dsp = AudioZoomDsp()
        assertEquals(1f, dsp.widthFor(1f), 0f)
        assertTrue(dsp.widthFor(2f) < 1f && dsp.widthFor(2f) > dsp.widthFor(4f))
        assertEquals(0.15f, dsp.widthFor(8f), 1e-4f)
        assertEquals(1f, dsp.gainFor(1f), 1e-4f)
    }

    @Test
    fun monoIsBoostedOnly() {
        val dsp = AudioZoomDsp()
        val pcm = ShortArray(1000) { 1000 }
        dsp.process(pcm, 1000, 1, 1f)
        dsp.process(pcm, 1000, 1, 5f)
        val again = ShortArray(1000) { 1000 }
        dsp.process(again, 1000, 1, 5f)
        assertEquals(1995.0, again[999].toDouble(), 10.0)
    }
}
