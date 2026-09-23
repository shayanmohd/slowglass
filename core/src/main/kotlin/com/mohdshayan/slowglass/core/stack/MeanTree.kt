package com.mohdshayan.slowglass.core.stack

/**
 * The schedule behind a true mean of any number of frames when every blend weight must stay at
 * or above 1/64 (half-float framebuffers lose a frame added with a smaller weight).
 *
 * Three levels: a block B holds the sum of up to 64 frames times 1/64; a superblock S holds up to
 * 64 blocks times 1/64; the accumulator A is the running mean of completed superblocks. The GPU
 * renderer follows [onFrame]'s steps exactly, and [MeanTree] runs the same steps on the CPU as the
 * reference the tests check.
 */
class MeanTreeSchedule {
    var frames: Long = 0
        private set
    var framesInBlock: Int = 0
        private set
    var blocksInSuper: Int = 0
        private set
    var superblocks: Long = 0
        private set

    enum class Step {
        /** B += frame / 64. */
        ADD_FRAME_TO_BLOCK,
        /** S += B / 64, then clear B. */
        FOLD_BLOCK_INTO_SUPER,
        /** A = A * (1 - 1/k) + S / k where k is [superblocks] after the fold, then clear S. */
        FOLD_SUPER_INTO_ACC,
    }

    /** 8-bit path: weight of this frame in the block's running mean, 1/k for the k-th frame. */
    var runningAddWeight: Float = 1f
        private set

    /** 8-bit path: weight of the block just completed in the superblock's running mean. */
    var runningBlockWeight: Float = 1f
        private set

    fun onFrame(): List<Step> {
        frames++
        framesInBlock++
        runningAddWeight = 1f / framesInBlock
        val steps = ArrayList<Step>(3)
        steps += Step.ADD_FRAME_TO_BLOCK
        if (framesInBlock == FANOUT) {
            framesInBlock = 0
            blocksInSuper++
            runningBlockWeight = 1f / blocksInSuper
            steps += Step.FOLD_BLOCK_INTO_SUPER
            if (blocksInSuper == FANOUT) {
                blocksInSuper = 0
                superblocks++
                steps += Step.FOLD_SUPER_INTO_ACC
            }
        }
        return steps
    }

    /** Weights (A, S, B) that turn the three buffers back into the mean of every frame so far. */
    fun resolveWeights(): FloatArray {
        if (frames == 0L) return floatArrayOf(0f, 0f, 0f)
        val n = frames.toDouble()
        val perSuper = (FANOUT * FANOUT).toDouble()
        return floatArrayOf(
            (superblocks * perSuper / n).toFloat(),
            (perSuper / n).toFloat(),
            (FANOUT / n).toFloat(),
        )
    }

    /**
     * Weights (A, S, B) for the 8-bit path, where every level holds a running mean instead of a sum
     * scaled by 1/64. An 8-bit buffer holding a sum over a few frames or blocks keeps only a handful of
     * levels, and resolving it multiplies that rounding back up; a running mean never does.
     */
    fun runningResolveWeights(): FloatArray {
        if (frames == 0L) return floatArrayOf(0f, 0f, 0f)
        val n = frames.toDouble()
        return floatArrayOf(
            (superblocks * FANOUT * FANOUT / n).toFloat(),
            (blocksInSuper * FANOUT / n).toFloat(),
            (framesInBlock / n).toFloat(),
        )
    }

    /** The blend weight used when a superblock folds into A; never below 1/k. */
    fun accFoldWeight(): Float = if (superblocks == 0L) 1f else 1f / superblocks

    fun reset() {
        frames = 0; framesInBlock = 0; blocksInSuper = 0; superblocks = 0
        runningAddWeight = 1f; runningBlockWeight = 1f
    }

    companion object {
        const val FANOUT = 64
        const val FRAME_WEIGHT = 1f / FANOUT
    }
}

/**
 * CPU reference for the GPU mean: same schedule, float buffers of any length. [quantize] models an
 * 8-bit framebuffer (it is applied to every stored value); [running] selects the 8-bit path's
 * running-mean levels instead of the half-float path's scaled sums.
 */
class MeanTree(
    private val size: Int,
    private val running: Boolean = false,
    private val quantize: ((Float) -> Float)? = null,
) {
    private val schedule = MeanTreeSchedule()
    private val a = FloatArray(size)
    private val s = FloatArray(size)
    private val b = FloatArray(size)

    val frames: Long get() = schedule.frames

    private fun q(v: Float): Float = quantize?.invoke(v) ?: v

    fun add(frame: FloatArray) {
        require(frame.size == size) { "frame size ${frame.size} is not $size" }
        for (step in schedule.onFrame()) when (step) {
            MeanTreeSchedule.Step.ADD_FRAME_TO_BLOCK -> if (running) {
                val w = schedule.runningAddWeight
                for (i in 0 until size) b[i] = q(b[i] * (1f - w) + frame[i] * w)
            } else {
                for (i in 0 until size) b[i] = q(b[i] + frame[i] * MeanTreeSchedule.FRAME_WEIGHT)
            }
            MeanTreeSchedule.Step.FOLD_BLOCK_INTO_SUPER -> {
                if (running) {
                    val w = schedule.runningBlockWeight
                    for (i in 0 until size) s[i] = q(s[i] * (1f - w) + b[i] * w)
                } else {
                    for (i in 0 until size) s[i] = q(s[i] + b[i] * MeanTreeSchedule.FRAME_WEIGHT)
                }
                b.fill(0f)
            }
            MeanTreeSchedule.Step.FOLD_SUPER_INTO_ACC -> {
                val w = schedule.accFoldWeight()
                for (i in 0 until size) a[i] = q(a[i] * (1f - w) + s[i] * w)
                s.fill(0f)
            }
        }
    }

    fun resolve(): FloatArray {
        val w = if (running) schedule.runningResolveWeights() else schedule.resolveWeights()
        return FloatArray(size) { i -> a[i] * w[0] + s[i] * w[1] + b[i] * w[2] }
    }
}

/** Per-pixel blend maths shared by the reference implementations and tests. */
object BlendMath {
    const val GAMMA = 2.2f

    fun lighten(dst: FloatArray, src: FloatArray) {
        for (i in dst.indices) if (src[i] > dst[i]) dst[i] = src[i]
    }

    fun linearise(v: Float): Float = Math.pow(v.toDouble(), GAMMA.toDouble()).toFloat()
    fun delinearise(v: Float): Float = Math.pow(v.coerceAtLeast(0f).toDouble(), 1.0 / GAMMA).toFloat()

    fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    fun luma(r: Float, g: Float, b: Float): Float = 0.2126f * r + 0.7152f * g + 0.0722f * b

    /**
     * Motion blur resolve for one channel: the mean, pulled toward the max where the max is bright,
     * so lamps stay sharp while moving people dissolve. [maxLuma] is the luma of the max pixel.
     */
    fun motionResolve(mean: Float, max: Float, maxLuma: Float, keepLights: Float): Float {
        val t = keepLights.coerceIn(0f, 1f) * smoothstep(0.6f, 1.0f, maxLuma)
        return mean + (max - mean) * t
    }
}
