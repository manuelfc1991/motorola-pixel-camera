package com.motorola.pixelcamera.computational

import org.junit.Assert.assertEquals
import org.junit.Test

class NightToneTest {

    /** The original per-pixel formula the lookup table replaced. */
    private fun original(v: Int): Int {
        val r = v * 1.25f
        val out = 255f * Math.pow((r / 255f).coerceIn(0f, 1f).toDouble(), 0.85).toFloat()
        return out.toInt().coerceIn(0, 255)
    }

    @Test
    fun lookupTableMatchesOriginalCurve() {
        for (v in 0..255) assertEquals("value $v", original(v), NightSightEngine.toneLut[v])
    }

    @Test
    fun curveLiftsShadowsAndKeepsRange() {
        assertEquals(0, NightSightEngine.toneLut[0])
        assertEquals(255, NightSightEngine.toneLut[255])
        assertEquals(true, NightSightEngine.toneLut[40] > 40)
    }
}
