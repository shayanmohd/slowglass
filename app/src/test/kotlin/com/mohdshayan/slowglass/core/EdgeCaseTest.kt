package com.mohdshayan.slowglass.core

import com.mohdshayan.slowglass.core.check.CheckMeasurements
import com.mohdshayan.slowglass.core.check.CheckVerdict
import com.mohdshayan.slowglass.core.check.RowStatus
import com.mohdshayan.slowglass.core.exif.ExifText
import com.mohdshayan.slowglass.core.export.DecodeResult
import com.mohdshayan.slowglass.core.export.ExportFile
import com.mohdshayan.slowglass.core.export.SessionCodec
import com.mohdshayan.slowglass.core.export.SessionCsv
import com.mohdshayan.slowglass.core.export.SessionDto
import com.mohdshayan.slowglass.core.lightning.SpikeDetector
import com.mohdshayan.slowglass.core.orient.OrientationMapper
import com.mohdshayan.slowglass.core.quirks.DeviceInfo
import com.mohdshayan.slowglass.core.quirks.QuirkRules
import com.mohdshayan.slowglass.core.sharp.SharpnessScore
import com.mohdshayan.slowglass.core.stack.MeanTree
import com.mohdshayan.slowglass.core.stack.MeanTreeSchedule
import com.mohdshayan.slowglass.core.stack.StackMode
import com.mohdshayan.slowglass.core.stars.GapBridge
import com.mohdshayan.slowglass.core.stars.GapTracker
import com.mohdshayan.slowglass.core.timer.BulbPreset
import com.mohdshayan.slowglass.core.timer.DimTimer
import com.mohdshayan.slowglass.core.timer.ExposureClock
import com.mohdshayan.slowglass.core.timer.SessionGuard
import com.mohdshayan.slowglass.core.timer.StopReason
import com.mohdshayan.slowglass.core.timer.TripodDelay
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

/** Zero, negative, huge, unit boundaries, time zones and corrupt files. */
class EdgeCaseTest {

    @Test
    fun clockHandlesUnitBoundariesAndHugeValues() {
        assertEquals("0:00", ExposureClock.format(0))
        assertEquals("0:00", ExposureClock.format(999))
        assertEquals("0:59", ExposureClock.format(59_999))
        assertEquals("1:00", ExposureClock.format(60_000))
        assertEquals("59:59", ExposureClock.format(3_599_999))
        assertEquals("1:00:00", ExposureClock.format(3_600_000))
        assertEquals("100:00:00", ExposureClock.format(360_000_000))
        assertEquals("0:00", ExposureClock.format(Long.MIN_VALUE))
        // Long.MAX_VALUE ms is about 292 million years; it must format, not throw.
        assertTrue(ExposureClock.format(Long.MAX_VALUE).endsWith(":55"))
        assertEquals(0f, ExposureClock.ringFraction(0), 0f)
        assertEquals(0f, ExposureClock.ringFraction(-1), 0f)
        assertEquals(1f, ExposureClock.ringFraction(60_000), 0f)
        assertEquals(0L, ExposureClock.laps(-60_000))
        assertFalse(ExposureClock.isDone(29_999, 30_000))
    }

    @Test
    fun clockKeepsLatinDigitsInEveryLocale() {
        val saved = Locale.getDefault()
        try {
            for (tag in listOf("ar-EG", "hi-IN-u-nu-deva", "fa-IR", "bn-BD")) {
                Locale.setDefault(Locale.forLanguageTag(tag))
                assertEquals(tag, "1:02:09", ExposureClock.format(3_729_000))
                assertEquals(tag, "Slowglass Silky water, 1 min 5 s, 1,950 frames stacked at 1080 x 1920",
                    ExifText.userComment("Silky water", 65_000, 1_950, 1080, 1920))
            }
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun presetLabelsAtTheirBoundaries() {
        assertEquals("1 s", BulbPreset.label(1_000))
        assertEquals("30 s", BulbPreset.label(30_000))
        assertEquals("1 min", BulbPreset.label(60_000))
        assertEquals("30 min", BulbPreset.label(1_800_000))
        assertEquals("Unlimited", BulbPreset.label(0))
        assertEquals(BulbPreset.DEFAULT_MS, BulbPreset.normalise(-30_000))
        assertEquals(BulbPreset.DEFAULT_MS, BulbPreset.normalise(Long.MAX_VALUE))
        assertEquals(0L, BulbPreset.normalise(0))
        assertEquals(TripodDelay.DEFAULT, TripodDelay.normalise(-2))
        assertEquals("Off", TripodDelay.label(0))
    }

    @Test
    fun exifTextForZeroSubSecondAndHourLongSessions() {
        assertEquals("0/1", ExifText.exposureTimeRational(0))
        assertEquals("0/1", ExifText.exposureTimeRational(-5))
        assertEquals("1/1", ExifText.exposureTimeRational(1_000))
        assertEquals("999/1000", ExifText.exposureTimeRational(999))
        assertEquals("Slowglass Light trails, 0.4 s, 12 frames stacked at 1920 x 1080",
            ExifText.userComment("Light trails", 400, 12, 1920, 1080))
        assertEquals("Slowglass Light trails, 0 s, 0 frames stacked at 1920 x 1080",
            ExifText.userComment("Light trails", 0, 0, 1920, 1080))
        assertEquals("Slowglass Lightning, 1 s, 1 frame stacked at 1080 x 1920",
            ExifText.userComment("Lightning", 1_000, 1, 1080, 1920))
        assertEquals("Slowglass Star trails, 1 min, 1,800 frames stacked at 1080 x 1920",
            ExifText.userComment("Star trails", 60_000, 1_800, 1080, 1920))
        assertEquals("Slowglass Star trails, 185 min 20 s, 111,600 frames stacked at 1080 x 1920",
            ExifText.userComment("Star trails", 11_120_000, 111_600, 1080, 1920))
    }

    private val base = SessionDto(
        startedAt = 1_741_500_000_000, endedAt = 1_741_500_030_000, mode = "trails",
        durationMs = 30_000, frameCount = 902, streamWidth = 1080, streamHeight = 1920,
    )

    @Test
    fun corruptImportFilesAreRejectedNotCrashing() {
        val bad = listOf(
            "",
            "   ",
            "\u0000\u0001\u0002PNG garbage",
            "[1, 2, 3]",
            "null",
            "{\"format\": \"slowglass-sessions\"",
            "{\"format\": 7}",
            "{\"format\": {\"x\": 1}}",
            "{\"format\": \"slowglass-sessions\", \"schema\": 0}",
            "{\"format\": \"slowglass-sessions\", \"schema\": \"one\"}",
            "{\"format\": \"slowglass-sessions\", \"sessions\": {}}",
            "{\"format\": \"slowglass-sessions\", \"sessions\": [{\"mode\": \"trails\"}]}",
            "{\"format\": \"slowglass-sessions\", \"sessions\": [{\"startedAt\": 1, \"endedAt\": 2, \"mode\": \"trails\", " +
                "\"durationMs\": 1, \"frameCount\": 99999999999, \"streamWidth\": 1, \"streamHeight\": 1}]}",
            "{\"format\": \"slowglass-sessions\", \"sessions\": [null]}",
        )
        for (text in bad) assertTrue("accepted: $text", SessionCodec.decode(text) is DecodeResult.Invalid)
        for (s in listOf(
            base.copy(durationMs = -1), base.copy(frameCount = -1), base.copy(droppedFrames = -3),
            base.copy(gapsBridged = -1), base.copy(streamWidth = -1080), base.copy(streamHeight = -1), base.copy(mode = ""),
        )) {
            assertTrue("accepted $s", SessionCodec.decode(SessionCodec.encode(ExportFile(sessions = listOf(s)))) is DecodeResult.Invalid)
        }
    }

    @Test
    fun importAcceptsEmptyLogsByteOrderMarksAndUnknownFields() {
        val empty = SessionCodec.decode("{\"format\":\"slowglass-sessions\",\"schema\":1}")
        assertTrue(empty is DecodeResult.Ok)
        assertEquals(0, (empty as DecodeResult.Ok).file.sessions.size)
        val withBom = "\uFEFF" + SessionCodec.encode(ExportFile(sessions = listOf(base)))
        val r = SessionCodec.decode(withBom)
        assertTrue(r is DecodeResult.Ok)
        assertEquals(listOf(base), (r as DecodeResult.Ok).file.sessions)
        val future = "{\"format\":\"slowglass-sessions\",\"schema\":1,\"addedLater\":[1,2],\"sessions\":[]}"
        assertTrue(SessionCodec.decode(future) is DecodeResult.Ok)
        assertEquals(emptyList<SessionDto>(), SessionCodec.newSessions(emptyList(), setOf(0L to "trails")))
    }

    @Test
    fun csvTimesCarryTheirOffsetAcrossDaylightSaving() {
        val ny = TimeZone.getTimeZone("America/New_York")
        // 2025-03-09 01:30 EST, then one hour later the clocks jump to 03:30 EDT.
        val before = 1_741_501_800_000L
        assertEquals("2025-03-09T01:30:00-05:00", SessionCsv.isoTime(before, ny))
        assertEquals("2025-03-09T03:30:00-04:00", SessionCsv.isoTime(before + 3_600_000, ny))
        // Autumn: 01:30 happens twice; the offset tells the two apart.
        val first = 1_762_061_400_000L
        assertEquals("2025-11-02T01:30:00-04:00", SessionCsv.isoTime(first, ny))
        assertEquals("2025-11-02T01:30:00-05:00", SessionCsv.isoTime(first + 3_600_000, ny))
        assertEquals("2025-03-09T11:00:00+05:30", SessionCsv.isoTime(1_741_498_200_000L, TimeZone.getTimeZone("Asia/Kolkata")))
        // A session that crosses the change still reports its real length, not wall-clock arithmetic.
        val csv = SessionCsv.encode(listOf(base.copy(startedAt = before, durationMs = 3_600_000))) { SessionCsv.isoTime(it, ny) }
        assertTrue(csv.lines()[1].startsWith("2025-03-09T01:30:00-05:00,trails,3600,"))
    }

    @Test
    fun exportFileDateFollowsTheLocalCalendar() {
        val instant = 1_758_663_000_000L // 2025-09-23 21:30 UTC
        assertEquals("2025-09-23", SessionCodec.fileDate(instant, TimeZone.getTimeZone("UTC")))
        assertEquals("2025-09-24", SessionCodec.fileDate(instant, TimeZone.getTimeZone("Asia/Kolkata")))
        assertEquals("2025-09-23", SessionCodec.fileDate(instant, TimeZone.getTimeZone("America/Los_Angeles")))
        assertEquals("slowglass-sessions-2025-09-24.csv", SessionCodec.fileName("2025-09-24", "csv"))
    }

    @Test
    fun csvEscapesEveryAwkwardCharacterAndSubSecondLengths() {
        assertEquals("plain", SessionCsv.escape("plain"))
        assertEquals("\"line\nbreak\"", SessionCsv.escape("line\nbreak"))
        assertEquals("\"cr\r\"", SessionCsv.escape("cr\r"))
        assertEquals("", SessionCsv.escape(""))
        val csv = SessionCsv.encode(listOf(base.copy(durationMs = 1_500, stackUri = null))) { "t" }
        assertEquals("t,trails,1.5,902,0,0,1080,1920,RGBA16F,0,", csv.lines()[1])
        assertEquals(SessionCsv.HEADER + "\n", SessionCsv.encode(emptyList()) { "t" })
    }

    @Test
    fun meanTreeWithNoFramesOrOneFrame() {
        val t = MeanTree(2)
        assertArrayEquals(floatArrayOf(0f, 0f), t.resolve(), 0f)
        t.add(floatArrayOf(0.25f, 1f))
        assertArrayEquals(floatArrayOf(0.25f, 1f), t.resolve(), 1e-6f)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f), MeanTreeSchedule().resolveWeights(), 0f)
        // Exactly one superblock: all weight moves to the accumulator.
        val s = MeanTreeSchedule()
        repeat(4096) { s.onFrame() }
        assertArrayEquals(floatArrayOf(1f, 1f, 64f / 4096f), s.resolveWeights(), 1e-6f)
    }

    @Test
    fun gapBridgeAndTrackerRejectNonsense() {
        assertEquals(0, GapBridge.radiusPx(-5.0, 30.0))
        assertEquals(0, GapBridge.radiusPx(10.0, 0.0))
        assertEquals(0, GapBridge.radiusPx(10.0, -1.0))
        assertEquals(3, GapBridge.radiusPx(1e9, 30.0))
        assertEquals(1920 / 75.0, GapBridge.pixelsPerDegree(0.0, 6.4, 1920), 1e-9)
        assertEquals(1920 / 75.0, GapBridge.pixelsPerDegree(5.4, -1.0, 1920), 1e-9)
        val t = GapTracker()
        assertEquals(0L, t.onFrame(1_000_000_000))
        assertEquals(0L, t.onFrame(1_000_000_000)) // duplicate timestamp
        assertEquals(0L, t.onFrame(900_000_000)) // clock went backwards
        assertEquals(0, t.droppedFrames)
    }

    @Test
    fun spikeDetectorNeedsHistoryAndSurvivesDarkAndSaturatedFrames() {
        val d = SpikeDetector(threshold = 0.07f)
        val w = 64
        val h = 36
        // A flash before any history never fires: the first frames of a session are not lightning.
        assertFalse(d.onFrame(FloatArray(w * h) { 1f }, w, h, 0))
        var ts = 33_000_000L
        repeat(40) { assertFalse(d.onFrame(FloatArray(w * h), w, h, ts)); ts += 33_000_000 }
        assertTrue(d.onFrame(FloatArray(w * h) { 1f }, w, h, ts))
        // A sky already at full white cannot rise, so it never fires.
        val white = SpikeDetector(threshold = 0.04f)
        ts = 0
        repeat(40) { assertFalse(white.onFrame(FloatArray(w * h) { 1f }, w, h, ts)); ts += 33_000_000 }
        d.reset()
        assertFalse(d.onFrame(FloatArray(w * h) { 1f }, w, h, ts))
    }

    @Test
    fun sharpnessOnDegenerateImages() {
        assertEquals(0.0, SharpnessScore.score(FloatArray(0), 0, 0), 0.0)
        assertEquals(0.0, SharpnessScore.score(FloatArray(4), 2, 2), 0.0)
        assertEquals(0.0, SharpnessScore.score(FloatArray(9) { 0.3f }, 3, 3), 1e-12)
        assertTrue(SharpnessScore.score(FloatArray(9) { if (it == 4) 1f else 0f }, 3, 3) >= 0.0)
    }

    @Test
    fun guardStopsAtTheBoundaryAndIgnoresUnknownBattery() {
        assertEquals(StopReason.BATTERY, SessionGuard.stopReason(0, charging = false, thermalStatus = 0))
        assertNull(SessionGuard.stopReason(-1, charging = false, thermalStatus = 0))
        assertNull(SessionGuard.stopReason(4, charging = false, thermalStatus = 3))
        assertEquals(StopReason.HEAT, SessionGuard.stopReason(100, charging = false, thermalStatus = 6))
        val dim = DimTimer(10_000)
        dim.touch(50_000)
        assertFalse(dim.shouldDim(40_000, sessionRunning = true, enabled = true)) // clock set back
        assertFalse(dim.shouldDim(90_000, sessionRunning = true, enabled = false))
    }

    @Test
    fun orientationWrapsOutOfRangeAngles() {
        val m = OrientationMapper()
        assertEquals(0, m.onAngle(360))
        assertEquals(90, m.onAngle(90 + 720))
        assertEquals(90, m.onAngle(-90)) // negative means unknown: keep the last
        assertEquals(0, OrientationMapper.snap(-10))
        assertEquals(270, OrientationMapper.snap(-90))
        assertEquals(0, OrientationMapper.snap(315))
        assertEquals(270, OrientationMapper.snap(314))
        m.reset(-90)
        assertEquals(270, m.held)
    }

    @Test
    fun checkVerdictForAStalledCameraAndAPortraitStream() {
        val stalled = CheckMeasurements(640, 480, 0f, halfFloat = false, lowFpsAccepted = false, infinityFocus = false,
            offers4k = false, fps4k = null, totalRamMb = 2_000)
        val r = CheckVerdict.build(stalled)
        assertTrue(r.verdict, r.verdict.startsWith("Your phone streams 640 x 480 at only 0.0 frames a second."))
        assertEquals(RowStatus.FAIL, r.rows.first { it.title == "Frame rate" }.status)
        assertEquals("RGBA8", r.precision)
        assertEquals(RowStatus.PASS, CheckVerdict.build(stalled.copy(streamWidth = 1080, streamHeight = 1920, measuredFps = 30f)).rows[0].status)
        assertEquals(0f, CheckVerdict.measureFps(5, 5, 100), 0f)
        assertEquals(0f, CheckVerdict.measureFps(10, 5, 100), 0f)
        assertEquals(0f, CheckVerdict.measureFps(0, 1_000_000_000, 1), 0f)
        assertEquals("9.5", CheckVerdict.fpsText(9.49f))
        assertEquals("10", CheckVerdict.fpsText(9.99f))
        assertTrue(CheckVerdict.shareText(r, "Samsung SM-A546E", "14").startsWith("Slowglass camera check\nPhone: Samsung SM-A546E, Android 14\n"))
    }

    @Test
    fun quirksNeverCrashOnOddFilesOrDevices() {
        assertEquals(0, QuirkRules.parse("").rules.size)
        assertEquals(0, QuirkRules.parse("[]").rules.size)
        val f = QuirkRules.parse("{\"rules\":[{\"modelPrefix\":\"\",\"forceRgba8\":true,\"minSdk\":40}]}")
        assertFalse(QuirkRules.resolve(f, DeviceInfo("", "", "", 36)).forceRgba8)
        assertTrue(QuirkRules.resolve(f, DeviceInfo("", "", "", 40)).forceRgba8)
        assertEquals(StackMode.TRAILS, StackMode.fromId(null))
        assertEquals(StackMode.TRAILS, StackMode.fromId("TRAILS"))
    }
}
