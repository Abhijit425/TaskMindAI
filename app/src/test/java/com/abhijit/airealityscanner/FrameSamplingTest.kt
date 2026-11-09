package com.abhijit.airealityscanner

import com.abhijit.airealityscanner.camera.FrameThrottle
import com.abhijit.airealityscanner.camera.SceneChange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameSamplingTest {

    @Test
    fun throttleAllowsOneFramePerInterval() {
        val throttle = FrameThrottle(1000L)
        assertTrue(throttle.tryAcquire(0))
        assertFalse(throttle.tryAcquire(500))
        assertFalse(throttle.tryAcquire(999))
        assertTrue(throttle.tryAcquire(1000))
        assertFalse(throttle.tryAcquire(1500))
    }

    @Test
    fun throttleResetAllowsImmediateFrame() {
        val throttle = FrameThrottle(1000L)
        assertTrue(throttle.tryAcquire(100))
        throttle.reset()
        assertTrue(throttle.tryAcquire(150))
    }

    @Test
    fun throttleUsesDynamicInterval() {
        var interval = 1000L
        val throttle = FrameThrottle { interval }
        assertTrue(throttle.tryAcquire(0))
        interval = 200L
        assertTrue(throttle.tryAcquire(250))
    }

    @Test
    fun identicalScenesHaveZeroDifference() {
        val a = FloatArray(256) { (it % 16) / 16f }
        assertEquals(0f, SceneChange.difference(a, a.copyOf()), 0.0001f)
        assertFalse(SceneChange.hasChanged(a, a.copyOf(), 0.1f))
    }

    @Test
    fun differentScenesAreDetected() {
        val dark = FloatArray(256) { 0.1f }
        val bright = FloatArray(256) { 0.8f }
        assertEquals(0.7f, SceneChange.difference(dark, bright), 0.0001f)
        assertTrue(SceneChange.hasChanged(dark, bright, 0.1f))
    }

    @Test
    fun firstFrameAlwaysCountsAsChange() {
        assertTrue(SceneChange.hasChanged(null, FloatArray(256), 0.5f))
    }
}
