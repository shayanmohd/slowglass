package com.mohdshayan.slowglass.core.sharp

/**
 * Variance of the Laplacian over a luminance image: high when edges are crisp, low when shake or
 * motion has blurred them. Used to keep the sharpest single frame of a session.
 */
object SharpnessScore {
    fun score(luma: FloatArray, width: Int, height: Int): Double {
        if (width < 3 || height < 3) return 0.0
        var sum = 0.0
        var sumSq = 0.0
        var n = 0
        for (y in 1 until height - 1) {
            val row = y * width
            for (x in 1 until width - 1) {
                val i = row + x
                val lap = (luma[i - 1] + luma[i + 1] + luma[i - width] + luma[i + width] - 4f * luma[i]).toDouble()
                sum += lap
                sumSq += lap * lap
                n++
            }
        }
        val mean = sum / n
        return sumSq / n - mean * mean
    }
}

/** Keeps the best score seen; [offer] is true when the new frame should replace the kept one. */
class SharpestKeeper {
    var best: Double = -1.0
        private set

    fun offer(score: Double): Boolean {
        if (score > best) {
            best = score
            return true
        }
        return false
    }

    fun reset() {
        best = -1.0
    }
}
