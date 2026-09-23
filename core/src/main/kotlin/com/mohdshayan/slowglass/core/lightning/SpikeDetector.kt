package com.mohdshayan.slowglass.core.lightning

/**
 * Finds lightning in a small luminance readback. The frame is split into [cols] x [rows] cells; each
 * cell's mean luminance is compared with the median of its last [history] values, and a strike fires
 * when any cell rises past [threshold]. Slow brightening (dawn, a passing cloud) moves the median with
 * it and never fires. After a strike the detector waits [refractoryNanos] before it can fire again.
 */
class SpikeDetector(
    var threshold: Float,
    private val cols: Int = 8,
    private val rows: Int = 4,
    private val history: Int = 30,
    private val refractoryNanos: Long = 500_000_000L,
) {
    private val cells = cols * rows
    private val ring = Array(cells) { FloatArray(history) }
    private var filled = 0
    private var cursor = 0
    private var lastFire = Long.MIN_VALUE
    private val scratch = FloatArray(history)

    /** Largest cell rise seen on the last call, for the strike's peak delta. */
    var lastPeak: Float = 0f
        private set

    /**
     * [luma] holds [width] x [height] values in 0..1, row-major. Returns true when this frame is a strike.
     */
    fun onFrame(luma: FloatArray, width: Int, height: Int, timestampNanos: Long): Boolean {
        val means = cellMeans(luma, width, height)
        var peak = 0f
        if (filled >= MIN_HISTORY) {
            for (c in 0 until cells) {
                val m = median(ring[c], filled)
                val d = means[c] - m
                if (d > peak) peak = d
            }
        }
        lastPeak = peak
        val fired = filled >= MIN_HISTORY && peak > threshold &&
            (lastFire == Long.MIN_VALUE || timestampNanos - lastFire >= refractoryNanos)
        if (fired) lastFire = timestampNanos
        // A strike frame is not history: keeping it would lift the median for the next second.
        if (!fired && (lastFire == Long.MIN_VALUE || timestampNanos - lastFire >= refractoryNanos)) {
            for (c in 0 until cells) ring[c][cursor] = means[c]
            cursor = (cursor + 1) % history
            if (filled < history) filled++
        }
        return fired
    }

    fun reset() {
        filled = 0; cursor = 0; lastFire = Long.MIN_VALUE; lastPeak = 0f
    }

    internal fun cellMeans(luma: FloatArray, width: Int, height: Int): FloatArray {
        val sums = FloatArray(cells)
        val counts = IntArray(cells)
        for (y in 0 until height) {
            val cy = (y * rows / height).coerceAtMost(rows - 1)
            for (x in 0 until width) {
                val cx = (x * cols / width).coerceAtMost(cols - 1)
                val c = cy * cols + cx
                sums[c] += luma[y * width + x]
                counts[c]++
            }
        }
        for (c in 0 until cells) if (counts[c] > 0) sums[c] /= counts[c]
        return sums
    }

    private fun median(values: FloatArray, n: Int): Float {
        System.arraycopy(values, 0, scratch, 0, n)
        scratch.sort(0, n)
        return if (n % 2 == 1) scratch[n / 2] else (scratch[n / 2 - 1] + scratch[n / 2]) / 2f
    }

    companion object {
        const val MIN_HISTORY = 5
    }
}
