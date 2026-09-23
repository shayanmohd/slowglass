package com.mohdshayan.slowglass.core.stack

/** The five capture modes. [id] is what Room, DataStore and the export file store. */
enum class StackMode(val id: String, val label: String, val shortLabel: String) {
    TRAILS("trails", "Light trails", "Trails"),
    WATER("water", "Silky water", "Water"),
    MOTION("motion", "Motion blur", "Motion"),
    STARS("stars", "Star trails", "Stars"),
    LIGHTNING("lightning", "Lightning", "Lightning");

    /** Modes that stack by lightening (per-channel max) rather than by averaging. */
    val lightens: Boolean get() = this == TRAILS || this == STARS || this == LIGHTNING

    companion object {
        fun fromId(id: String?): StackMode = entries.firstOrNull { it.id == id } ?: TRAILS
    }
}

/** Light trails: frames averaged per sub-exposure before lightening, so noise does not ratchet up. */
enum class NoiseSmoothing(val id: String, val label: String, val framesPerSub: Int) {
    OFF("off", "Off", 1),
    LOW("low", "Low", 3),
    HIGH("high", "High", 8);

    companion object {
        fun fromId(id: String?): NoiseSmoothing = entries.firstOrNull { it.id == id } ?: LOW
    }
}

/** Lightning trigger threshold on the rise of one cell's luminance over its rolling median. */
enum class LightningSensitivity(val id: String, val label: String, val threshold: Float) {
    LOW("low", "Low", 0.12f),
    MEDIUM("medium", "Medium", 0.07f),
    HIGH("high", "High", 0.04f);

    companion object {
        fun fromId(id: String?): LightningSensitivity = entries.firstOrNull { it.id == id } ?: MEDIUM
    }
}

object StarSensitivity {
    const val MIN = 4
    const val MAX = 30
    const val DEFAULT = 8
    fun clamp(frames: Int): Int = frames.coerceIn(MIN, MAX)
}

/** Counts frames into sub-exposures of [framesPerSub]; true from [onFrame] when one completes. */
class SubExposureCounter(framesPerSub: Int) {
    val framesPerSub: Int = framesPerSub.coerceAtLeast(1)
    var inSub: Int = 0
        private set
    var completedSubs: Long = 0
        private set

    /** Weight the next frame gets inside its sub-exposure, so the sub is a true mean. */
    val frameWeight: Float get() = 1f / framesPerSub

    fun onFrame(): Boolean {
        inSub++
        if (inSub >= framesPerSub) {
            inSub = 0
            completedSubs++
            return true
        }
        return false
    }

    fun reset() {
        inSub = 0
        completedSubs = 0
    }
}
