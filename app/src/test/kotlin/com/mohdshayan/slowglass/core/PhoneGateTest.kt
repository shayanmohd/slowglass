package com.mohdshayan.slowglass.core

import com.mohdshayan.slowglass.core.exif.ExifText
import com.mohdshayan.slowglass.core.orient.OrientationLatch
import com.mohdshayan.slowglass.core.timer.InProgressSession
import com.mohdshayan.slowglass.core.timer.SessionPulse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The logic behind the fixes from the first Samsung Galaxy S22+ pass. */
class PhoneGateTest {

    @Test
    fun aPhoneLyingFlatFromLaunchTakesTheDisplayOrientation() {
        // Flat and unmoved: the listener reports only unknown angles.
        val o = OrientationLatch()
        repeat(50) { o.onAngle(-1) }
        assertNull(o.lastReliable)
        assertEquals(0, o.latch(displayRotationDegrees = 0))
        // Every session in a row decides the same way, so modes can no longer disagree.
        assertEquals(0, o.latch(displayRotationDegrees = 0))
        // A display turned counter-clockwise (ROTATION_90) is a phone the listener calls 270.
        assertEquals(270, o.latch(displayRotationDegrees = 90))
        assertEquals(90, o.latch(displayRotationDegrees = 270))
        assertEquals(180, o.latch(displayRotationDegrees = 180))
    }

    @Test
    fun aPhoneLaidFlatKeepsItsLastReliableReading() {
        val o = OrientationLatch()
        o.onAngle(88) // held in landscape, left side up
        repeat(20) { o.onAngle(-1) } // then laid flat on the table
        assertEquals(90, o.latch(displayRotationDegrees = 0))
    }

    @Test
    fun theFirstReadingSnapsWithoutHysteresis() {
        val o = OrientationLatch()
        o.onAngle(50) // nearer landscape than portrait
        assertEquals(90, o.lastReliable)
        o.onAngle(40) // inside the hysteresis band now: stays
        assertEquals(90, o.lastReliable)
        o.onAngle(10)
        assertEquals(0, o.lastReliable)
    }

    @Test
    fun readingsFromAnEarlierVisitAreForgotten() {
        // The stale reading that made one flat session portrait and the next landscape.
        val o = OrientationLatch()
        o.onAngle(270)
        o.forget() // the camera closed; the phone may have been turned and laid flat since
        o.onAngle(-1)
        assertEquals(0, o.latch(displayRotationDegrees = 0))
    }

    @Test
    fun theNotificationAndRecordPulseEveryFifteenSecondsOrOnAStrike() {
        assertEquals(15_000L, SessionPulse.EVERY_MS)
        assertTrue(SessionPulse.due(0, lastPulseMs = null, strikesPosted = 0, strikes = 0))
        assertFalse(SessionPulse.due(1_000, lastPulseMs = 0, strikesPosted = 0, strikes = 0))
        assertFalse(SessionPulse.due(14_999, lastPulseMs = 0, strikesPosted = 0, strikes = 0))
        assertTrue(SessionPulse.due(15_000, lastPulseMs = 0, strikesPosted = 0, strikes = 0))
        assertTrue(SessionPulse.due(3_000, lastPulseMs = 0, strikesPosted = 0, strikes = 1))
        // A 13 minute star session posts about 53 times, not 780.
        var posts = 0
        var last: Long? = null
        for (t in 0L..13 * 60_000L step 250) {
            if (SessionPulse.due(t, last, 0, 0)) { posts++; last = t }
        }
        assertEquals(53, posts)
    }

    @Test
    fun aKilledSessionIsNamedWithHowLongItRan() {
        val start = 1_758_700_000_000L
        assertEquals(
            "Your last exposure (Star trails) was interrupted before it could be saved. It had run for at least 6 min 45 s.",
            InProgressSession("stars", start, start + 405_000, 0).message(),
        )
        assertEquals(
            "Your last exposure (Light trails) was interrupted before it could be saved. It had run for less than 15 s.",
            InProgressSession("trails", start, start, 0).message(),
        )
        assertEquals(
            "Your last exposure (Lightning) was interrupted before it could be saved. It had run for at least 2 min. " +
                "The 2 strikes it caught were saved to Pictures/Slowglass.",
            InProgressSession("lightning", start, start + 120_000, 2).message(),
        )
        assertTrue(InProgressSession("lightning", start, start + 30_000, 1).message().endsWith("The strike it caught was saved to Pictures/Slowglass."))
    }

    @Test
    fun theSharpestFrameGetsTheRealFrameDuration() {
        assertEquals("1/30", ExifText.frameExposureRational(33_333_333))
        assertEquals("1/10", ExifText.frameExposureRational(100_000_000))
        assertEquals("1/15", ExifText.frameExposureRational(66_700_000))
        assertEquals("1/2", ExifText.frameExposureRational(500_000_000))
        assertEquals("1/1", ExifText.frameExposureRational(999_000_000))
        assertEquals("2/1", ExifText.frameExposureRational(2_000_000_000))
        assertNull(ExifText.frameExposureRational(0))
        assertNull(ExifText.frameExposureRational(-5))
    }

    @Test
    fun lengthWordsMatchTheExifDescription() {
        assertEquals("42 s", ExifText.length(42_400))
        assertEquals("6 min 45 s", ExifText.length(405_000))
        assertEquals("2 min", ExifText.length(120_000))
        assertEquals("0.4 s", ExifText.length(400))
    }
}
