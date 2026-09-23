package com.mohdshayan.slowglass.core

import com.mohdshayan.slowglass.core.lightning.SpikeDetector
import com.mohdshayan.slowglass.core.stars.GapBridge
import com.mohdshayan.slowglass.core.stars.GapTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StarsAndLightningTest {

    @Test
    fun gapBridgeRadiusFollowsSiderealDriftAndCapsAtThree() {
        assertEquals(2, GapBridge.radiusPx(10.0, 25.6))
        assertEquals(3, GapBridge.radiusPx(60.0, 25.6))
        assertEquals(0, GapBridge.radiusPx(0.0, 25.6))
        // 1 s at 31 px per degree drifts 0.13 px: still one pixel of bridging.
        assertEquals(1, GapBridge.radiusPx(1.0, 31.0))
    }

    @Test
    fun pixelsPerDegreeFromOptics() {
        assertEquals(31.3, GapBridge.pixelsPerDegree(5.4, 6.4, 1920), 0.1)
        assertEquals(1920 / 75.0, GapBridge.pixelsPerDegree(null, null, 1920), 1e-9)
        assertEquals(62.6, GapBridge.pixelsPerDegree(5.4, 6.4, 1920, zoom = 2.0), 0.2)
    }

    @Test
    fun gapTrackerReportsLongGapsAndCountsDrops() {
        val t = GapTracker()
        val frame = 33_333_333L
        var ts = 0L
        repeat(10) { assertEquals(0L, t.onFrame(ts)); ts += frame }
        ts += frame * 2 // two frames missing
        assertEquals(0L, t.onFrame(ts))
        assertEquals(2, t.droppedFrames)
        ts += 2_000_000_000L
        assertEquals(2_000_000_000L, t.onFrame(ts))
    }

    private fun frame(level: Float, hot: Float = level, w: Int = 64, h: Int = 36): FloatArray {
        // Top-left cell (8 px by 9 px) at [hot], the rest at [level].
        return FloatArray(w * h) { i -> if (i % w < 8 && i / w < 9) hot else level }
    }

    @Test
    fun spikeDetectorFiresOnA008CellRiseAtMedium() {
        val d = SpikeDetector(threshold = 0.07f)
        var ts = 0L
        repeat(30) { assertFalse(d.onFrame(frame(0.20f), 64, 36, ts)); ts += 33_000_000 }
        assertTrue(d.onFrame(frame(0.20f, hot = 0.28f), 64, 36, ts))
        assertEquals(0.08f, d.lastPeak, 1e-4f)
        // Within the half-second wait a second flash does not fire again.
        ts += 100_000_000
        assertFalse(d.onFrame(frame(0.20f, hot = 0.40f), 64, 36, ts))
    }

    @Test
    fun spikeDetectorIgnoresSlowBrighteningAndSmallRises() {
        val d = SpikeDetector(threshold = 0.07f)
        var ts = 0L
        var level = 0.10f
        repeat(600) {
            assertFalse("frame $it", d.onFrame(frame(level), 64, 36, ts))
            level += 0.001f // 0.6 over 20 seconds: dawn, not lightning
            ts += 33_000_000
        }
        val low = SpikeDetector(threshold = 0.12f)
        ts = 0
        repeat(30) { low.onFrame(frame(0.2f), 64, 36, ts); ts += 33_000_000 }
        assertFalse(low.onFrame(frame(0.2f, hot = 0.28f), 64, 36, ts))
    }
}
