package com.mohdshayan.slowglass.core.orient

/**
 * Decides the saved photo's rotation once, at session start. A phone lying flat gives the orientation
 * listener no usable angle, so the photo takes the last reliable reading seen while the viewfinder
 * was open, and when there is none, the display's own orientation. Readings from an earlier visit
 * are forgotten when the camera closes, because the phone may have been turned since.
 */
class OrientationLatch(hysteresisDegrees: Int = 15) {
    private val mapper = OrientationMapper(hysteresisDegrees)

    /** How the phone was last reliably held (0, 90, 180 or 270), or null when nothing reliable came yet. */
    var lastReliable: Int? = null
        private set

    /** [angle] is the listener's 0..359, or -1 when the phone lies flat and the angle is unknown. */
    fun onAngle(angle: Int) {
        if (angle < 0) return
        // The first reading snaps straight to the nearest side; hysteresis applies only after it.
        if (lastReliable == null) mapper.reset(angle)
        lastReliable = mapper.onAngle(angle)
    }

    /** The camera closed: the next session must not trust what the phone did before. */
    fun forget() {
        lastReliable = null
    }

    /**
     * The clockwise rotation for the photo. [displayRotationDegrees] is the display's rotation
     * (Surface.ROTATION_* as 0, 90, 180 or 270), used only when no reliable reading exists.
     */
    fun latch(displayRotationDegrees: Int): Int {
        val held = lastReliable ?: displayToHeld(displayRotationDegrees)
        return OrientationMapper.photoRotation(held)
    }

    companion object {
        /**
         * The display turns its content the opposite way to the phone: a display at ROTATION_90 is
         * a phone turned counter-clockwise, which the orientation listener reports as 270.
         */
        fun displayToHeld(displayRotationDegrees: Int): Int =
            OrientationMapper.snap((360 - OrientationMapper.snap(displayRotationDegrees)) % 360)
    }
}
