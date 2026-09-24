package com.mohdshayan.slowglass.capture

import org.junit.Assert.assertEquals
import org.junit.Test

/** The shutter's readiness: CameraX's published state, frames at the renderer, and the stall wait. */
class CameraReadinessTest {

    private fun CameraReadiness.frames(n: Int): CamStatus {
        var s = status
        repeat(n) { s = onFrame() }
        return s
    }

    @Test
    fun publishedOpenMakesTheShutterReady() {
        val r = CameraReadiness()
        assertEquals(CamStatus.STARTING, r.onBind(0))
        assertEquals(CamStatus.STARTING, r.onPublished(open = false, error = null, nowMs = 100))
        assertEquals(CamStatus.READY, r.onPublished(open = true, error = null, nowMs = 400))
    }

    @Test
    fun framesMakeTheShutterReadyWhenOpenIsNeverPublished() {
        // The S22+ after Lightning, Done, Star trails: a rebind from 1080p to 4K replayed the old
        // OPEN, then published CLOSING and OPENING and never OPEN, while frames were flowing.
        val r = CameraReadiness(stallMs = 6_000, framesToTrust = 3)
        r.onBind(0)
        assertEquals(CamStatus.READY, r.onPublished(open = true, error = null, nowMs = 1)) // stale replay
        assertEquals(CamStatus.STARTING, r.onPublished(open = false, error = null, nowMs = 5)) // CLOSING
        assertEquals(CamStatus.STARTING, r.onPublished(open = false, error = null, nowMs = 40)) // OPENING
        assertEquals(CamStatus.STARTING, r.frames(2))
        assertEquals(CamStatus.READY, r.onFrame())
        // No OPEN ever comes; the watchdog must not undo a camera that is streaming.
        assertEquals(CamStatus.READY, r.onTick(60_000, wantOpen = true))
        assertEquals(CamStatus.READY, r.frames(100))
    }

    @Test
    fun aStateChangeRestartsTheFrameCount() {
        val r = CameraReadiness(framesToTrust = 3)
        r.onBind(0)
        r.frames(2)
        r.onPublished(open = false, error = null, nowMs = 10)
        assertEquals(CamStatus.STARTING, r.frames(2))
        assertEquals(CamStatus.READY, r.onFrame())
    }

    @Test
    fun noOpenAndNoFramesStallsWithARetry() {
        val r = CameraReadiness(stallMs = 6_000)
        r.onBind(1_000)
        r.onPublished(open = false, error = null, nowMs = 1_010) // OPENING
        assertEquals(CamStatus.STARTING, r.onTick(6_999, wantOpen = true))
        assertEquals(CamStatus.STALLED, r.onTick(7_000, wantOpen = true))
        // Stays stalled, with its Retry, through further OPENING or CLOSING.
        assertEquals(CamStatus.STALLED, r.onPublished(open = false, error = null, nowMs = 8_000))
        // Frames or OPEN clear it.
        assertEquals(CamStatus.READY, r.frames(3))
    }

    @Test
    fun aCameraThatKeepsCyclingStillStalls() {
        val r = CameraReadiness(stallMs = 6_000)
        r.onBind(0)
        for (t in 500L..5_500L step 500) r.onPublished(open = false, error = null, nowMs = t)
        assertEquals(CamStatus.STALLED, r.onTick(6_000, wantOpen = true))
    }

    @Test
    fun timeSpentClosedNeverCountsAsAStall() {
        val r = CameraReadiness(stallMs = 6_000)
        r.onBind(0)
        r.onTick(60_000, wantOpen = false) // the screen was away; opening restarts the wait
        assertEquals(CamStatus.STARTING, r.onTick(65_000, wantOpen = true))
        assertEquals(CamStatus.STALLED, r.onTick(66_000, wantOpen = true))
    }

    @Test
    fun errorsMapThroughAndFramesStillProveTheCameraOpen() {
        val r = CameraReadiness()
        r.onBind(0)
        assertEquals(CamStatus.IN_USE, r.onPublished(open = false, error = CamStatus.IN_USE, nowMs = 10))
        assertEquals(CamStatus.READY, r.frames(3))
        assertEquals(CamStatus.DISABLED, r.onPublished(open = false, error = CamStatus.DISABLED, nowMs = 20))
    }

    @Test
    fun hardFailuresHoldUntilTheNextBind() {
        val r = CameraReadiness()
        r.onBind(0)
        assertEquals(CamStatus.FAILED, r.onFailure(CamStatus.FAILED))
        assertEquals(CamStatus.FAILED, r.frames(10))
        assertEquals(CamStatus.FAILED, r.onPublished(open = true, error = null, nowMs = 10))
        assertEquals(CamStatus.FAILED, r.onTick(60_000, wantOpen = true))
        assertEquals(CamStatus.STARTING, r.onBind(70_000))
        assertEquals(CamStatus.READY, r.onPublished(open = true, error = null, nowMs = 70_100))
    }

    @Test
    fun aNewBindForgetsTheOldStream() {
        val r = CameraReadiness(framesToTrust = 3)
        r.onBind(0)
        r.frames(3)
        assertEquals(CamStatus.STARTING, r.onBind(1_000))
        assertEquals(CamStatus.STARTING, r.frames(2))
        assertEquals(CamStatus.READY, r.onFrame())
    }
}
