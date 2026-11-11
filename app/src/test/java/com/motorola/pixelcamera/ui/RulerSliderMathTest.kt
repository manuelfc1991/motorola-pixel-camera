package com.motorola.pixelcamera.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class RulerSliderMathTest {

    @Test
    fun draggingLeftRaisesTheValue() {
        assertEquals(13f, RulerSliderView.valueAfterDrag(10f, -60f, 20f, 100), 0.001f)
        assertEquals(7f, RulerSliderView.valueAfterDrag(10f, 60f, 20f, 100), 0.001f)
    }

    @Test
    fun valueStaysInRange() {
        assertEquals(0f, RulerSliderView.valueAfterDrag(2f, 500f, 20f, 8), 0.001f)
        assertEquals(8f, RulerSliderView.valueAfterDrag(2f, -500f, 20f, 8), 0.001f)
    }

    @Test
    fun shortScalesGetFourTicksPerValue() {
        assertEquals(4, RulerSliderView.ticksPerValue(8))
        assertEquals(1, RulerSliderView.ticksPerValue(100))
    }
}
