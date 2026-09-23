package com.mohdshayan.slowglass.core.check

import java.util.Locale

/** What the five second camera check measured on this phone. */
data class CheckMeasurements(
    val streamWidth: Int,
    val streamHeight: Int,
    val measuredFps: Float,
    val halfFloat: Boolean,
    val lowFpsAccepted: Boolean,
    val infinityFocus: Boolean,
    /** 4K stream size offered by the camera for a GPU texture. */
    val offers4k: Boolean,
    /** Frames a second measured while streaming 4K, or null when 4K was not tried. */
    val fps4k: Float?,
    val totalRamMb: Long,
)

enum class RowStatus { PASS, NOTE, FAIL }

data class CheckRow(val title: String, val detail: String, val status: RowStatus)

data class CheckReport(
    val rows: List<CheckRow>,
    val verdict: String,
    val holds4k: Boolean,
    val outputWidth: Int,
    val outputHeight: Int,
    val precision: String,
)

/**
 * Turns measurements into plain sentences the user can act on, and decides the output size.
 * 4K is used only when the camera offers it, the GPU kept at least 24 frames a second at that size,
 * the phone has 6 GB of memory or more (motion blur needs about 330 MB of buffers at 4K), and the
 * half-float path exists.
 */
object CheckVerdict {
    const val MIN_4K_FPS = 24f
    const val MIN_4K_RAM_MB = 5_600L
    const val MIN_USABLE_FPS = 10f

    fun holds4k(m: CheckMeasurements): Boolean =
        m.offers4k && m.halfFloat && (m.fps4k ?: 0f) >= MIN_4K_FPS && m.totalRamMb >= MIN_4K_RAM_MB

    fun build(m: CheckMeasurements): CheckReport {
        val use4k = holds4k(m)
        val outW = if (use4k) 3840 else m.streamWidth
        val outH = if (use4k) 2160 else m.streamHeight
        val fps = fpsText(m.measuredFps)
        val rows = mutableListOf<CheckRow>()
        rows += CheckRow(
            "Stream size",
            "${m.streamWidth} x ${m.streamHeight}",
            if (m.streamWidth >= 1920 || m.streamHeight >= 1920) RowStatus.PASS else RowStatus.NOTE,
        )
        rows += CheckRow(
            "Frame rate",
            "$fps frames a second",
            if (m.measuredFps >= MIN_USABLE_FPS) RowStatus.PASS else RowStatus.FAIL,
        )
        rows += CheckRow(
            "Stacking precision",
            if (m.halfFloat) "16-bit, smooth over long sessions" else "8-bit with dithering. Long silky water sessions show more banding.",
            if (m.halfFloat) RowStatus.PASS else RowStatus.NOTE,
        )
        rows += CheckRow(
            "Low frame rate for stars",
            if (m.lowFpsAccepted) "Accepted" else "Your phone did not accept a low frame rate. Star trails will use the default rate.",
            if (m.lowFpsAccepted) RowStatus.PASS else RowStatus.NOTE,
        )
        rows += CheckRow(
            "Infinity focus",
            if (m.infinityFocus) "Supported, stars focus on their own" else "Not supported. Tap a bright star to focus.",
            if (m.infinityFocus) RowStatus.PASS else RowStatus.NOTE,
        )
        rows += CheckRow(
            "4K stacking",
            when {
                use4k -> "Yes, stacks save at 3840 x 2160"
                !m.offers4k -> "The camera does not stream 4K to apps"
                m.totalRamMb < MIN_4K_RAM_MB -> "Kept at ${m.streamWidth} x ${m.streamHeight} to leave memory free"
                else -> "Kept at ${m.streamWidth} x ${m.streamHeight}, 4K ran at ${fpsText(m.fps4k ?: 0f)} frames a second"
            },
            if (use4k) RowStatus.PASS else RowStatus.NOTE,
        )
        val verdict = if (m.measuredFps < MIN_USABLE_FPS) {
            "Your phone streams ${m.streamWidth} x ${m.streamHeight} at only $fps frames a second. " +
                "Stacks will save, but trails will look dotted. Close other camera apps and run the check again."
        } else {
            "Your phone streams ${m.streamWidth} x ${m.streamHeight} at $fps frames a second. Stacks save at $outW x $outH."
        }
        return CheckReport(rows, verdict, use4k, outW, outH, if (m.halfFloat) "RGBA16F" else "RGBA8")
    }

    /** Whole numbers from 10 up, one decimal below; 9.97 reads "10", never "10.0". */
    fun fpsText(fps: Float): String {
        val tenths = Math.round(fps.coerceAtLeast(0f) * 10f) / 10f
        return if (tenths >= 10f) Math.round(tenths).toString() else String.format(Locale.US, "%.1f", tenths)
    }

    /** The report the user can share in a support email. */
    fun shareText(report: CheckReport, device: String, androidVersion: String): String = buildString {
        appendLine("Slowglass camera check")
        appendLine("Phone: $device, Android $androidVersion")
        for (r in report.rows) appendLine("${r.title}: ${r.detail}")
        appendLine()
        append(report.verdict)
    }

    /** Frames a second from first and last timestamps in nanoseconds over [frames] frames. */
    fun measureFps(firstNanos: Long, lastNanos: Long, frames: Int): Float {
        if (frames < 2 || lastNanos <= firstNanos) return 0f
        return (frames - 1) * 1_000_000_000f / (lastNanos - firstNanos)
    }
}
