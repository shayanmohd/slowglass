package com.mohdshayan.slowglass.core

import com.mohdshayan.slowglass.core.exif.ExifText
import com.mohdshayan.slowglass.core.orient.OrientationMapper
import com.mohdshayan.slowglass.core.sharp.SharpestKeeper
import com.mohdshayan.slowglass.core.sharp.SharpnessScore
import com.mohdshayan.slowglass.core.timer.BulbPreset
import com.mohdshayan.slowglass.core.timer.DimTimer
import com.mohdshayan.slowglass.core.timer.ExposureClock
import com.mohdshayan.slowglass.core.timer.SessionGuard
import com.mohdshayan.slowglass.core.timer.StopReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputAndTimingTest {

    @Test
    fun sharpnessRanksACheckerboardAboveItsBlur() {
        val w = 32
        val h = 32
        val board = FloatArray(w * h) { i -> if (((i % w) / 4 + (i / w) / 4) % 2 == 0) 0.9f else 0.1f }
        val blurred = FloatArray(w * h) { i ->
            val x = i % w
            val y = i / w
            var s = 0f
            var n = 0
            for (dy in -2..2) for (dx in -2..2) {
                val xx = (x + dx).coerceIn(0, w - 1)
                val yy = (y + dy).coerceIn(0, h - 1)
                s += board[yy * w + xx]; n++
            }
            s / n
        }
        val sharp = SharpnessScore.score(board, w, h)
        val soft = SharpnessScore.score(blurred, w, h)
        assertTrue("sharp $sharp soft $soft", sharp > soft * 4)
        val flat = SharpnessScore.score(FloatArray(w * h) { 0.5f }, w, h)
        assertEquals(0.0, flat, 1e-9)
        val keeper = SharpestKeeper()
        assertTrue(keeper.offer(soft))
        assertTrue(keeper.offer(sharp))
        assertFalse(keeper.offer(soft))
    }

    @Test
    fun exposureClockFormatsAndDrawsOneLapPerMinute() {
        assertEquals("0:24", ExposureClock.format(24_900))
        assertEquals("38:12", ExposureClock.format(38 * 60_000L + 12_000))
        assertEquals("1:02:09", ExposureClock.format(3_729_000))
        assertEquals("0:00", ExposureClock.format(-5))
        assertEquals(0.4f, ExposureClock.ringFraction(24_000), 1e-6f)
        assertEquals(0.5f, ExposureClock.ringFraction(90_000), 1e-6f)
        assertEquals(1L, ExposureClock.laps(90_000))
        assertEquals(24_000L, ExposureClock.quantise(24_730, reducedMotion = true))
        assertEquals(24_730L, ExposureClock.quantise(24_730, reducedMotion = false))
        assertTrue(ExposureClock.isDone(30_000, 30_000))
        assertFalse(ExposureClock.isDone(99_999_999, BulbPreset.UNLIMITED))
        assertEquals("∞", BulbPreset.chip(0))
        assertEquals("2m", BulbPreset.chip(120_000))
        assertEquals(BulbPreset.DEFAULT_MS, BulbPreset.normalise(7_000))
    }

    @Test
    fun exifTextSaysHowThePhotoWasMade() {
        assertEquals(
            "Slowglass Light trails, 42 s, 1,260 frames stacked at 1920 x 1080",
            ExifText.userComment("Light trails", 42_400, 1260, 1920, 1080),
        )
        assertEquals(
            "Slowglass Star trails, 38 min 12 s, 68,760 frames stacked at 1080 x 1920",
            ExifText.userComment("Star trails", 2_292_000, 68_760, 1080, 1920),
        )
        assertEquals("42/1", ExifText.exposureTimeRational(42_000))
        assertEquals("1500/1000", ExifText.exposureTimeRational(1_500))
    }

    @Test
    fun orientationSnapsWithHysteresisAndRotatesThePhoto() {
        val m = OrientationMapper()
        assertEquals(0, m.onAngle(50)) // inside the hysteresis band
        assertEquals(90, m.onAngle(80))
        assertEquals(90, m.onAngle(35)) // not far enough back to flip
        assertEquals(0, m.onAngle(20))
        assertEquals(270, m.onAngle(280))
        assertEquals(270, m.onAngle(-1)) // flat: keep the last
        assertEquals(0, m.onAngle(355))
        assertEquals(90, OrientationMapper.photoRotation(90))
        assertEquals(270, OrientationMapper.photoRotation(270))
        assertEquals(1920 to 1080, OrientationMapper.rotatedSize(1080, 1920, 90))
        assertEquals(1080 to 1920, OrientationMapper.rotatedSize(1080, 1920, 180))
    }

    @Test
    fun sessionGuardStopsForBatteryAndHeatOnly() {
        assertEquals(StopReason.BATTERY, SessionGuard.stopReason(3, charging = false, thermalStatus = 0))
        assertNull(SessionGuard.stopReason(3, charging = true, thermalStatus = 0))
        assertNull(SessionGuard.stopReason(4, charging = false, thermalStatus = 3))
        assertEquals(StopReason.HEAT, SessionGuard.stopReason(80, charging = true, thermalStatus = 4))
        assertTrue(SessionGuard.stackThisFrame(1, 2))
        assertFalse(SessionGuard.stackThisFrame(1, 3))
        assertTrue(SessionGuard.stackThisFrame(2, 3))
        val dim = DimTimer(10_000)
        dim.touch(1_000)
        assertFalse(dim.shouldDim(10_999, sessionRunning = true, enabled = true))
        assertTrue(dim.shouldDim(11_000, sessionRunning = true, enabled = true))
        assertFalse(dim.shouldDim(11_000, sessionRunning = false, enabled = true))
    }
}
