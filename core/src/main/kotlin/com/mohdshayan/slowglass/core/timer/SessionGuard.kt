package com.mohdshayan.slowglass.core.timer

/** Why a session ended. Every reason still saves what was stacked. */
enum class StopReason(val message: String?) {
    USER(null),
    PRESET(null),
    BATTERY("Battery at 3 percent, so the session saved and stopped."),
    HEAT("The phone got too hot, so the session saved and stopped."),
    CAMERA_LOST("The camera closed, so the session saved what it had."),
}

/**
 * Battery and heat rules for long sessions. Thermal levels follow PowerManager's THERMAL_STATUS_*
 * values (0 none to 6 shutdown) without importing Android.
 */
object SessionGuard {
    const val BATTERY_STOP_PERCENT = 3
    const val THERMAL_SEVERE = 3
    const val THERMAL_CRITICAL = 4

    fun stopReason(batteryPercent: Int, charging: Boolean, thermalStatus: Int): StopReason? = when {
        thermalStatus >= THERMAL_CRITICAL -> StopReason.HEAT
        !charging && batteryPercent in 0..BATTERY_STOP_PERCENT -> StopReason.BATTERY
        else -> null
    }

    /** At SEVERE heat only every other frame is stacked, halving the GPU load. */
    fun stackThisFrame(frameIndex: Long, thermalStatus: Int): Boolean =
        thermalStatus < THERMAL_SEVERE || frameIndex % 2L == 0L
}

/** When the viewfinder dims during a session: after [idleMs] without a touch. */
class DimTimer(private val idleMs: Long = 10_000) {
    private var lastTouch = 0L

    fun touch(nowMs: Long) {
        lastTouch = nowMs
    }

    fun shouldDim(nowMs: Long, sessionRunning: Boolean, enabled: Boolean): Boolean =
        enabled && sessionRunning && nowMs - lastTouch >= idleMs
}
