package com.mohdshayan.slowglass.core.timer

import com.mohdshayan.slowglass.core.exif.ExifText
import com.mohdshayan.slowglass.core.stack.StackMode

/**
 * How often a running session re-posts its notification and records that it is still alive. The
 * notification's clock is a system chronometer, so only the frame count waits for this; posting
 * rarely keeps an always-on display from waking during a screen-off star session.
 */
object SessionPulse {
    const val EVERY_MS = 15_000L

    /** True when the notification text and the in-progress record are due for an update. */
    fun due(elapsedMs: Long, lastPulseMs: Long?, strikesPosted: Int, strikes: Int): Boolean =
        lastPulseMs == null || elapsedMs - lastPulseMs >= EVERY_MS || strikes != strikesPosted
}

/**
 * A session recorded as in progress. It is written when stacking starts, refreshed every
 * [SessionPulse.EVERY_MS] and cleared when the session ends in any way the app sees. One still
 * present at the next launch belonged to a process that was killed before it could save.
 */
data class InProgressSession(
    val modeId: String,
    val startedAtMs: Long,
    val lastAliveMs: Long,
    val strikes: Int,
) {
    /** The plain words the capture screen shows on the next launch. */
    fun message(): String {
        val mode = StackMode.fromId(modeId)
        val ran = lastAliveMs - startedAtMs
        val length = if (ran < SessionPulse.EVERY_MS) {
            "less than ${ExifText.length(SessionPulse.EVERY_MS)}"
        } else {
            "at least ${ExifText.length(ran)}"
        }
        val caught = when {
            mode != StackMode.LIGHTNING || strikes <= 0 -> ""
            strikes == 1 -> " The strike it caught was saved to Pictures/Slowglass."
            else -> " The $strikes strikes it caught were saved to Pictures/Slowglass."
        }
        return "Your last exposure (${mode.label}) was interrupted before it could be saved. It had run for $length.$caught"
    }
}
