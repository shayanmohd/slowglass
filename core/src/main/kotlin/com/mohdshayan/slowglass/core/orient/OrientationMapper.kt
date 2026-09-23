package com.mohdshayan.slowglass.core.orient

/**
 * Turns the raw angle from an orientation listener into how the phone is held, with hysteresis so a
 * phone resting near 45 degrees does not flip back and forth, and into the clockwise rotation the
 * saved photo needs. The viewfinder is portrait, so a phone held in landscape rotates its photo.
 */
class OrientationMapper(private val hysteresisDegrees: Int = 15) {
    /** One of 0, 90, 180, 270: device rotation as reported by the sensor angle. */
    var held: Int = 0
        private set

    /** [angle] is 0..359, or -1 when the phone lies flat and the angle is unknown. */
    fun onAngle(angle: Int): Int {
        if (angle < 0) return held
        val a = ((angle % 360) + 360) % 360
        val distance = angularDistance(a, held)
        if (distance > 45 + hysteresisDegrees) {
            held = snap(a)
        }
        return held
    }

    fun reset(value: Int = 0) {
        held = snap(value)
    }

    companion object {
        fun snap(angle: Int): Int {
            val a = ((angle % 360) + 360) % 360
            return ((a + 45) / 90 % 4) * 90
        }

        private fun angularDistance(a: Int, b: Int): Int {
            val d = kotlin.math.abs(a - b) % 360
            return if (d > 180) 360 - d else d
        }

        /**
         * Clockwise rotation to apply to the portrait image so it is upright as the phone was held.
         * The listener reports 90 when the phone's left edge is at the top (turned clockwise); the
         * scene's up then points to the image's left, so the image turns 90 degrees clockwise.
         */
        fun photoRotation(held: Int): Int = snap(held)

        /** Output width and height after rotating a [w] x [h] image by [rotation] degrees. */
        fun rotatedSize(w: Int, h: Int, rotation: Int): Pair<Int, Int> =
            if (rotation % 180 == 0) w to h else h to w
    }
}
