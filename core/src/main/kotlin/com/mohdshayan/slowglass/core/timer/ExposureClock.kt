package com.mohdshayan.slowglass.core.timer

import java.util.Locale

/** Exposure length presets. 0 ms is Unlimited: the session runs until the user stops it. */
object BulbPreset {
    val presetsMs: List<Long> = listOf(
        1_000, 2_000, 4_000, 8_000, 15_000, 30_000,
        60_000, 120_000, 300_000, 600_000, 1_800_000, 0,
    )
    const val UNLIMITED = 0L
    const val DEFAULT_MS = 30_000L

    fun label(ms: Long): String = when {
        ms == UNLIMITED -> "Unlimited"
        ms < 60_000 -> "${ms / 1000} s"
        else -> "${ms / 60_000} min"
    }

    /** Short chip text in the engraved face, for example 30s, 2m, or an infinity sign. */
    fun chip(ms: Long): String = when {
        ms == UNLIMITED -> "∞"
        ms < 60_000 -> "${ms / 1000}s"
        else -> "${ms / 60_000}m"
    }

    fun normalise(ms: Long): Long = if (ms in presetsMs) ms else DEFAULT_MS
}

object TripodDelay {
    val options: List<Int> = listOf(0, 2, 5, 10)
    const val DEFAULT = 2
    fun label(s: Int): String = if (s == 0) "Off" else "$s s"
    fun normalise(s: Int): Int = if (s in options) s else DEFAULT
}

object ExposureClock {
    /** 0:24, 12:05 or 1:02:09. Never negative. */
    fun format(elapsedMs: Long): String {
        val total = (elapsedMs.coerceAtLeast(0) / 1000)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        // Locale.US: the engraved face has Latin digits only, whatever the phone's language.
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
    }

    /** Fraction of the current lap of the trail ring, which draws one lap per minute. */
    fun ringFraction(elapsedMs: Long, lapMs: Long = 60_000): Float {
        if (elapsedMs <= 0) return 0f
        val inLap = elapsedMs % lapMs
        return if (inLap == 0L) 1f else inLap.toFloat() / lapMs
    }

    /** Completed laps of the ring, so the UI can show them as a quieter full circle underneath. */
    fun laps(elapsedMs: Long, lapMs: Long = 60_000): Long = (elapsedMs.coerceAtLeast(0) / lapMs)

    /** Under reduced motion the ring advances once per second instead of per frame. */
    fun quantise(elapsedMs: Long, reducedMotion: Boolean): Long =
        if (reducedMotion) elapsedMs - elapsedMs % 1000 else elapsedMs

    /** True when a timed session has reached its preset. Unlimited never finishes on its own. */
    fun isDone(elapsedMs: Long, presetMs: Long): Boolean =
        presetMs != BulbPreset.UNLIMITED && elapsedMs >= presetMs
}
