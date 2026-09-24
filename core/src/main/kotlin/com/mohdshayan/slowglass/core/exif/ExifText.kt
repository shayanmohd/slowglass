package com.mohdshayan.slowglass.core.exif

import java.util.Locale

/** The words Slowglass writes into each photo's EXIF, so the file itself says how it was made. */
object ExifText {
    /** EXIF ExposureTime as a rational string: whole seconds as "42/1", sub-second as "1/2". */
    fun exposureTimeRational(durationMs: Long): String {
        if (durationMs <= 0) return "0/1"
        if (durationMs % 1000 == 0L) return "${durationMs / 1000}/1"
        return "$durationMs/1000"
    }

    /**
     * EXIF ExposureTime for one frame of the stream, from the average frame interval in nanoseconds:
     * "1/30" at 30 frames a second, "1/10" at 10, whole seconds as "2/1". Unknown (0 or less) is null.
     */
    fun frameExposureRational(frameNs: Long): String? {
        if (frameNs <= 0) return null
        if (frameNs >= 1_000_000_000L) return exposureTimeRational((frameNs + 500_000) / 1_000_000)
        val perSecond = Math.round(1_000_000_000.0 / frameNs).coerceAtLeast(1)
        return "1/$perSecond"
    }

    /** 42 s, 6 min 45 s, 2 min, or 0.4 s inside the first second. */
    fun length(durationMs: Long): String {
        val seconds = durationMs.coerceAtLeast(0) / 1000
        return if (durationMs in 1 until 1000) {
            // A session stopped inside its first second still says how long it ran.
            String.format(Locale.US, "%.1f s", durationMs / 1000.0)
        } else if (seconds >= 60) {
            val m = seconds / 60
            val s = seconds % 60
            if (s == 0L) "$m min" else "$m min $s s"
        } else {
            "$seconds s"
        }
    }

    /** Slowglass Light trails, 42 s, 1,260 frames stacked at 1920 x 1080 */
    fun userComment(modeLabel: String, durationMs: Long, frames: Int, width: Int, height: Int): String {
        val length = length(durationMs)
        val count = String.format(Locale.US, "%,d", frames)
        val noun = if (frames == 1) "frame" else "frames"
        return "Slowglass $modeLabel, $length, $count $noun stacked at $width x $height"
    }

    /** File names: Slowglass_20260923_214510_trails.jpg and ..._sharpest.jpg. */
    fun fileStem(yyyymmddHHmmss: String, modeId: String): String = "Slowglass_${yyyymmddHHmmss}_$modeId"
}
