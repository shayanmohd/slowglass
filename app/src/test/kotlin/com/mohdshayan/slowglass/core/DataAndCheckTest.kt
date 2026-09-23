package com.mohdshayan.slowglass.core

import com.mohdshayan.slowglass.core.check.CheckMeasurements
import com.mohdshayan.slowglass.core.check.CheckVerdict
import com.mohdshayan.slowglass.core.export.CameraCheckDto
import com.mohdshayan.slowglass.core.export.DecodeResult
import com.mohdshayan.slowglass.core.export.ExportFile
import com.mohdshayan.slowglass.core.export.SessionCodec
import com.mohdshayan.slowglass.core.export.SessionCsv
import com.mohdshayan.slowglass.core.export.SessionDto
import com.mohdshayan.slowglass.core.export.StrikeDto
import com.mohdshayan.slowglass.core.quirks.DeviceInfo
import com.mohdshayan.slowglass.core.quirks.QuirkRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DataAndCheckTest {

    private val storm = SessionDto(
        startedAt = 1_758_640_000_000, endedAt = 1_758_640_312_000, mode = "lightning",
        durationMs = 312_000, frameCount = 9_342, droppedFrames = 4, streamWidth = 1080, streamHeight = 1920,
        stackUri = "content://media/external/images/media/88", note = "Sahyadri ridge, west window",
        strikes = listOf(StrikeDto(1_758_640_101_000, 0.143f, "content://media/external/images/media/86")),
    )
    private val trails = storm.copy(startedAt = 1_758_600_000_000, mode = "trails", durationMs = 30_000, frameCount = 902, strikes = emptyList())

    @Test
    fun sessionCodecRoundTripsEveryField() {
        val file = ExportFile(
            prefs = mapOf("default_mode" to "stars"),
            sessions = listOf(storm, trails),
            cameraChecks = listOf(CameraCheckDto(1, "0", 1920, 1080, 29.8f, true, true, false, "ok")),
        )
        val back = SessionCodec.decode(SessionCodec.encode(file))
        assertTrue(back is DecodeResult.Ok)
        assertEquals(file, (back as DecodeResult.Ok).file)
    }

    @Test
    fun sessionCodecRejectsOtherFiles() {
        assertTrue(SessionCodec.decode("{\"hello\": 1}") is DecodeResult.Invalid)
        assertTrue(SessionCodec.decode("not json at all") is DecodeResult.Invalid)
        assertTrue(SessionCodec.decode("{\"format\":\"slowglass-sessions\",\"schema\":9}") is DecodeResult.Invalid)
        val badMode = SessionCodec.encode(ExportFile(sessions = listOf(trails.copy(mode = "video"))))
        assertTrue(SessionCodec.decode(badMode) is DecodeResult.Invalid)
    }

    @Test
    fun importMergesOnStartTimeAndMode() {
        val existing = setOf(SessionCodec.key(trails))
        val incoming = listOf(trails, storm, storm, trails.copy(mode = "water"))
        val added = SessionCodec.newSessions(incoming, existing)
        assertEquals(listOf(storm, trails.copy(mode = "water")), added)
    }

    @Test
    fun csvHasTheDocumentedColumnsAndEscapesNotes() {
        val csv = SessionCsv.encode(listOf(storm, trails)) { "T$it" }
        val lines = csv.trimEnd().lines()
        assertEquals(SessionCsv.HEADER, lines[0])
        assertEquals("T1758640000000,lightning,312,9342,4,0,1080,1920,RGBA16F,1,content://media/external/images/media/88", lines[1])
        assertEquals("\"a, \"\"b\"\"\"", SessionCsv.escape("a, \"b\""))
    }

    private val m = CheckMeasurements(1920, 1080, 29.7f, halfFloat = true, lowFpsAccepted = true, infinityFocus = false,
        offers4k = true, fps4k = 29.1f, totalRamMb = 7_600)

    @Test
    fun checkPicks4kOnlyWhenEverythingHolds() {
        assertTrue(CheckVerdict.holds4k(m))
        assertFalse(CheckVerdict.holds4k(m.copy(totalRamMb = 3_700)))
        assertFalse(CheckVerdict.holds4k(m.copy(fps4k = 17f)))
        assertFalse(CheckVerdict.holds4k(m.copy(halfFloat = false)))
        val r = CheckVerdict.build(m.copy(offers4k = false, fps4k = null))
        assertEquals("Your phone streams 1920 x 1080 at 30 frames a second. Stacks save at 1920 x 1080.", r.verdict)
        assertEquals(1920, r.outputWidth)
        assertEquals("Stacks save at 3840 x 2160.", CheckVerdict.build(m).verdict.substringAfter("second. "))
        assertEquals(29.5f, CheckVerdict.measureFps(0, 2_000_000_000, 60), 0.01f)
    }

    @Test
    fun quirkRulesMatchOnlyNamedDevices() {
        val file = QuirkRules.parse(
            """{"schema":1,"rules":[
              {"manufacturer":"acme","modelPrefix":"AX-","cap1080p":true},
              {"hardware":"ranchu","skipFpsRequest":true},
              {"forceRgba8":true}
            ]}""",
        )
        val acme = QuirkRules.resolve(file, DeviceInfo("ACME", "AX-200", "qcom", 34))
        assertTrue(acme.cap1080p)
        assertFalse(acme.forceRgba8) // a rule naming no device never matches everything
        val emu = QuirkRules.resolve(file, DeviceInfo("Google", "sdk_gphone64_arm64", "ranchu", 36))
        assertTrue(emu.skipFpsRequest)
        assertFalse(emu.cap1080p)
        assertEquals(0, QuirkRules.parse("{broken").rules.size)
    }
}
