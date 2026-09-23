package com.mohdshayan.slowglass.core.stars

import kotlin.math.atan
import kotlin.math.ceil

/**
 * Star trails survive a gap in the camera stream. Stars drift 15.04 arcseconds per second, so after
 * a gap the next sub-exposure is max-filtered wide enough to join the trail across the missing
 * frames, and never wider than 3 px so a short gap does not smear the whole sky.
 */
object GapBridge {
    const val SIDEREAL_ARCSEC_PER_SECOND = 15.04
    const val MAX_RADIUS_PX = 3
    const val FALLBACK_FOV_DEGREES = 75.0

    fun radiusPx(gapSeconds: Double, pixelsPerDegree: Double): Int {
        if (gapSeconds <= 0.0 || pixelsPerDegree <= 0.0) return 0
        val drift = gapSeconds * SIDEREAL_ARCSEC_PER_SECOND / 3600.0 * pixelsPerDegree
        return ceil(drift - 1e-9).toInt().coerceIn(0, MAX_RADIUS_PX)
    }

    /**
     * Pixels per degree across the stream's long side from the lens focal length and the sensor's
     * physical width, both in millimetres. Falls back to a 75 degree field when optics are unknown.
     */
    fun pixelsPerDegree(focalMm: Double?, sensorWidthMm: Double?, streamLongSidePx: Int, zoom: Double = 1.0): Double {
        val fovDeg = if (focalMm != null && sensorWidthMm != null && focalMm > 0 && sensorWidthMm > 0) {
            Math.toDegrees(2.0 * atan(sensorWidthMm / (2.0 * focalMm)))
        } else {
            FALLBACK_FOV_DEGREES
        }
        return streamLongSidePx / fovDeg * zoom.coerceAtLeast(1e-3)
    }
}

/**
 * Watches frame timestamps and reports a gap when two frames arrive further apart than [gapNanos].
 * Also counts frames the stream should have delivered but did not, for the session's dropped count.
 */
class GapTracker(private val gapNanos: Long = 1_000_000_000L) {
    private var last = -1L
    private var intervalEstimate = 0L
    var droppedFrames: Int = 0
        private set

    /** Returns the gap length in nanoseconds when this frame follows a gap, else 0. */
    fun onFrame(timestampNanos: Long): Long {
        val prev = last
        last = timestampNanos
        if (prev < 0) return 0
        val dt = timestampNanos - prev
        if (dt <= 0) return 0
        if (intervalEstimate == 0L) intervalEstimate = dt
        if (dt > intervalEstimate * 3 / 2 && intervalEstimate > 0) {
            droppedFrames += ((dt / intervalEstimate) - 1).toInt().coerceAtLeast(1)
        } else {
            intervalEstimate = (intervalEstimate * 7 + dt) / 8
        }
        return if (dt >= gapNanos) dt else 0
    }

    fun reset() {
        last = -1; intervalEstimate = 0; droppedFrames = 0
    }
}
