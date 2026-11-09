package com.abhijit.airealityscanner.camera

import kotlin.math.abs

/**
 * Allows at most one event per [minIntervalMs]. Used to throttle on-device ML and cloud uploads so
 * the app never processes (or uploads) every camera frame.
 */
class FrameThrottle(private val minIntervalMs: () -> Long) {
    private var lastAccepted = Long.MIN_VALUE

    constructor(fixedIntervalMs: Long) : this({ fixedIntervalMs })

    @Synchronized
    fun tryAcquire(nowMs: Long): Boolean {
        if (lastAccepted != Long.MIN_VALUE && nowMs - lastAccepted < minIntervalMs()) return false
        lastAccepted = nowMs
        return true
    }

    @Synchronized
    fun reset() {
        lastAccepted = Long.MIN_VALUE
    }
}

/** Scene change detection using mean absolute difference between low-resolution luminance grids. */
object SceneChange {
    /** Returns 0 (identical) … 1 (completely different). */
    fun difference(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 1f
        var total = 0f
        for (i in a.indices) total += abs(a[i] - b[i])
        return total / a.size
    }

    fun hasChanged(previous: FloatArray?, current: FloatArray, threshold: Float): Boolean =
        previous == null || difference(previous, current) >= threshold
}
